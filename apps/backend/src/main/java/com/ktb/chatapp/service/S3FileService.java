package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.MalformedURLException;
import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@Primary // LocalFileService 대신 이걸 씀
@RequiredArgsConstructor
public class S3FileService implements FileService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final FileRepository fileRepository;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    @Override
    public FileUploadResult uploadFile(MultipartFile file, String uploaderId) {
        try {
            FileUtil.validateFile(file);

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null)
                originalFilename = "file";
            String safeFileName = FileUtil.generateSafeFileName(StringUtils.cleanPath(originalFilename));

            // S3 Upload
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(safeFileName)
                    .contentType(file.getContentType())
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            log.info("S3 파일 업로드 성공: {}", safeFileName);

            // DB 저장
            File fileEntity = File.builder()
                    .filename(safeFileName) // S3 Key
                    .originalname(FileUtil.normalizeOriginalFilename(originalFilename))
                    .mimetype(file.getContentType())
                    .size(file.getSize())
                    .path("s3://" + bucketName + "/" + safeFileName) // S3 경로 표기
                    .user(uploaderId)
                    .uploadDate(LocalDateTime.now())
                    .build();

            File savedFile = fileRepository.save(fileEntity);

            return FileUploadResult.builder()
                    .success(true)
                    .file(savedFile)
                    .build();

        } catch (Exception e) {
            log.error("S3 파일 업로드 실패", e);
            throw new RuntimeException("파일 업로드 실패: " + e.getMessage());
        }
    }

    @Override
    public String storeFile(MultipartFile file, String subDirectory) {
        try {
            FileUtil.validateFile(file);

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null)
                originalFilename = "file";
            String safeFileName = FileUtil.generateSafeFileName(StringUtils.cleanPath(originalFilename));

            String key = (subDirectory != null && !subDirectory.isEmpty())
                    ? subDirectory + "/" + safeFileName
                    : safeFileName;

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType())
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            // Public URL 반환 (프로필 이미지 용)
            // 주의: 버킷이 Private이면 이 URL로는 접근 불가. Presigned URL이나 CloudFront 필요.
            // 하지만 요구사항상 프론트에서 S3 URL을 쓴다면 Presigned URL을 매번 발급받는건 프로필 이미지에 적합하지 않음(캐싱 안됨).
            // 보통 프로필 이미지는 Public Read ACL을 주거나 CloudFront를 씀.
            // 여기서는 getUrl()로 반환하고, 버킷 정책에 맡김.
            return s3Client.utilities().getUrl(GetUrlRequest.builder().bucket(bucketName).key(key).build())
                    .toExternalForm();

        } catch (Exception e) {
            throw new RuntimeException("S3 파일 저장 실패: " + e.getMessage());
        }
    }

    // 이 메서드는 이제 리다이렉트 용 Presigned URL을 Resource 내용으로 담거나 해야함.
    // 하지만 인터페이스 시그니처가 Resource를 반환하므로, S3 URL을 담은 UrlResource를 반환.
    // **중요**: 만약 Private 버킷이면 UrlResource로 읽을 때 403 뜸.
    // 따라서 Presigned URL을 생성해서 UrlResource로 감싸서 반환.
    @Override
    public Resource loadFileAsResource(String fileName, String requesterId) {
        try {
            // 1. 파일 확인
            File fileEntity = fileRepository.findByFilename(fileName)
                    .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

            // 2. 권한 검증 (채팅방 참여자)
            Message message = messageRepository.findByFileId(fileEntity.getId())
                    .orElseThrow(() -> new RuntimeException("메시지 없음"));
            Room room = roomRepository.findById(message.getRoomId())
                    .orElseThrow(() -> new RuntimeException("방 없음"));

            if (!room.getParticipantIds().contains(requesterId)) {
                throw new RuntimeException("권한 없음");
            }

            // 3. Presigned URL 생성 (유효기간 1시간)
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(b -> b.bucket(bucketName).key(fileName))
                    .build();

            String presignedUrl = s3Presigner.presignGetObject(presignRequest).url().toExternalForm();

            return new UrlResource(presignedUrl);

        } catch (MalformedURLException e) {
            throw new RuntimeException("URL 생성 실패", e);
        }
    }

    @Override
    public boolean deleteFile(String fileId, String requesterId) {
        try {
            File file = fileRepository.findById(fileId).orElseThrow(() -> new RuntimeException("파일 없음"));

            if (!file.getUser().equals(requesterId)) {
                throw new RuntimeException("권한 없음");
            }

            // S3 삭제
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(file.getFilename()).build());

            fileRepository.delete(file);
            return true;
        } catch (Exception e) {
            log.error("S3 파일 삭제 실패", e);
            return false;
        }
    }

    @Override
    public void deleteFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isEmpty()) {
            return;
        }

        try {
            // URL에서 Key 추출 (예: https://bucket.s3.region.amazonaws.com/key)
            // 간단하게 마지막 슬래시 뒤의 파일명만 추출하거나, URL 구조에 따라 파싱 필요.
            // 여기서는 전체 URL이 들어온다고 가정하고, 키 추출 시도.

            // 만약 profileImage가 /uploads/... 로컬 경로라면 무시 (단, 마이그레이션 과도기엔 필요할 수 있음)
            if (fileUrl.startsWith("/")) {
                return; // S3 서비스에서는 로컬 경로 무시
            }

            String key = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
            // 만약 "profiles/" 같은 서브디렉토리가 키에 포함되어야 한다면 더 정교한 파싱 필요.
            // storeFile에서 "profiles/" + safeFileName을 저장하고 URL을 반환했으므로,
            // URL의 마지막 부분만으로는 폴더 경로가 유실될 수 있음.

            // storeFile의 반환값: s3Client.utilities().getUrl(...).toExternalForm()
            // URL 구조: https://bucket.s3.region.amazonaws.com/profiles/filename
            // 따라서 bucketName 뒤의 경로를 가져와야 함.

            java.net.URL url = new java.net.URL(fileUrl);
            String path = url.getPath(); // /profiles/filename
            if (path.startsWith("/")) {
                path = path.substring(1); // profiles/filename
            }

            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(path).build());
            log.info("S3 파일 삭제 성공: {}", path);

        } catch (Exception e) {
            log.warn("S3 파일 삭제 실패 (URL: {}): {}", fileUrl, e.getMessage());
        }
    }
}
