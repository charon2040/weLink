package com.epsilon.welink.common.controller;

import com.epsilon.welink.common.DegradationManager;
import com.epsilon.welink.common.entity.FileMeta;
import com.epsilon.welink.common.exception.BusinessException;
import com.epsilon.welink.common.result.Result;
import com.epsilon.welink.common.result.ResultCode;
import com.epsilon.welink.common.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/file")
public class FileController {

    private final FileStorageService fileStorageService;
    private final DegradationManager degradationManager;

    public FileController(FileStorageService fileStorageService, DegradationManager degradationManager) {
        this.fileStorageService = fileStorageService;
        this.degradationManager = degradationManager;
    }

    @PostMapping("/upload")
    public Result<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file,
                                                  @RequestAttribute("userId") Long uploaderId) {
        if (!degradationManager.isFileUploadEnabled()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "系统繁忙，文件上传暂时关闭");
        }
        FileMeta meta = fileStorageService.uploadFile(file, uploaderId);
        return Result.success(Map.of(
                "fileId", meta.getFileId(),
                "filename", meta.getOriginalFilename() != null ? meta.getOriginalFilename() : "",
                "size", meta.getSize() != null ? meta.getSize() : 0,
                "mimeType", meta.getMimeType() != null ? meta.getMimeType() : "",
                "url", "/api/v1/files/" + meta.getFileId()
        ));
    }
}

