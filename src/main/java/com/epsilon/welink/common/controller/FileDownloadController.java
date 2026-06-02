package com.epsilon.welink.common.controller;

import com.epsilon.welink.common.entity.FileMeta;
import com.epsilon.welink.common.service.FileStorageService;
import com.epsilon.welink.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件下载代理 + 元数据查询. 独立在 /api/v1/files/ 路径(与上传 /api/v1/file/upload 分开)
 * 让 JWT 拦截器能用 excludePathPatterns("/api/v1/files/*") 放行 GET 请求.
 * <p>
 * 设计:
 * <ul>
 *   <li>GET /{fileId}            → 302 到 MinIO presigned, 浏览器内联显示 (img/video/pdf)</li>
 *   <li>GET /{fileId}?download=1 → 302 到 MinIO presigned 但带 attachment, 强制下载</li>
 *   <li>GET /{fileId}/meta       → 返回 JSON 元数据 (filename/size/mime), 用于消息渲染</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files")
public class FileDownloadController {

    private final FileStorageService fileStorageService;

    public FileDownloadController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/{fileId}")
    public void download(@PathVariable String fileId,
                         @RequestParam(value = "download", required = false) String download,
                         HttpServletResponse response) throws java.io.IOException {
        FileMeta meta = fileStorageService.getFileMeta(fileId);
        if (meta == null) {
            response.sendError(404, "file not found");
            return;
        }
        boolean forceDownload = "1".equals(download) || "true".equalsIgnoreCase(download);
        String url = fileStorageService.signTemporaryUrl(fileId, forceDownload ? meta.getOriginalFilename() : null);
        if (url == null) {
            response.sendError(500, "failed to sign url");
            return;
        }
        response.setHeader("Cache-Control", "private, max-age=300");
        response.sendRedirect(url);
    }

    @GetMapping("/{fileId}/meta")
    public Result<Map<String, Object>> meta(@PathVariable String fileId) {
        FileMeta meta = fileStorageService.getFileMeta(fileId);
        if (meta == null) {
            return Result.error(404, "file not found");
        }
        Map<String, Object> m = new HashMap<>();
        m.put("fileId", meta.getFileId());
        m.put("filename", meta.getOriginalFilename());
        m.put("size", meta.getSize());
        m.put("mimeType", meta.getMimeType());
        m.put("uploaderId", meta.getUploaderId());
        return Result.success(m);
    }
}
