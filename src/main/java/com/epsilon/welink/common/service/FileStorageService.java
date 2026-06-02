package com.epsilon.welink.common.service;

import com.epsilon.welink.common.entity.FileMeta;
import com.epsilon.welink.common.mapper.FileMetaMapper;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@Service
public class FileStorageService {

    private final MinioClient minioClient;
    private final FileMetaMapper fileMetaMapper;

    @Value("${welink.minio.bucket-name}")
    private String bucketName;

    public FileStorageService(MinioClient minioClient, FileMetaMapper fileMetaMapper) {
        this.minioClient = minioClient;
        this.fileMetaMapper = fileMetaMapper;
    }

    /**
     * 上传文件: 存入 MinIO + 写 file_meta 元数据行. 返回元数据(含 fileId, 但不含 url; URL 走 /file/{fileId} 代理签).
     */
    public FileMeta uploadFile(MultipartFile file, Long uploaderId) {
        try {
            String objectName = generateObjectName(file.getOriginalFilename());
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );

            FileMeta meta = new FileMeta();
            meta.setFileId(UUID.randomUUID().toString());
            meta.setObjectName(objectName);
            meta.setOriginalFilename(file.getOriginalFilename());
            meta.setSize(file.getSize());
            meta.setMimeType(file.getContentType());
            meta.setUploaderId(uploaderId);
            fileMetaMapper.insert(meta);
            return meta;
        } catch (Exception e) {
            log.error("Failed to upload file", e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 给 fileId 当场签 1 小时 presigned URL.
     * @param fileId fileMeta.file_id
     * @param attachmentFilename 非 null 时附加 response-content-disposition=attachment 让浏览器强制下载;
     *                           null 时浏览器按 MIME 默认行为 (图片/PDF 内联)
     */
    public String signTemporaryUrl(String fileId, String attachmentFilename) {
        FileMeta meta = fileMetaMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId));
        if (meta == null) return null;
        try {
            GetPresignedObjectUrlArgs.Builder argsBuilder = GetPresignedObjectUrlArgs.builder()
                    .bucket(bucketName)
                    .object(meta.getObjectName())
                    .method(Method.GET)
                    .expiry(1, java.util.concurrent.TimeUnit.HOURS);
            if (attachmentFilename != null && !attachmentFilename.isBlank()) {
                // MinIO 支持 response-content-disposition / response-content-type 透传到生成的 URL
                String encoded = java.net.URLEncoder.encode(attachmentFilename, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20");
                java.util.Map<String, String> extraParams = new java.util.HashMap<>();
                extraParams.put("response-content-disposition",
                        "attachment; filename=\"" + attachmentFilename.replace("\"", "") + "\"; filename*=UTF-8''" + encoded);
                argsBuilder.extraQueryParams(extraParams);
            }
            return minioClient.getPresignedObjectUrl(argsBuilder.build());
        } catch (Exception e) {
            log.error("Failed to sign temporary url for fileId={}", fileId, e);
            return null;
        }
    }

    /** 向后兼容: 无 attachment 形式. */
    public String signTemporaryUrl(String fileId) {
        return signTemporaryUrl(fileId, null);
    }

    public FileMeta getFileMeta(String fileId) {
        return fileMetaMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId));
    }

    private String generateObjectName(String originalFilename) {
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        return UUID.randomUUID().toString() + extension;
    }
}
