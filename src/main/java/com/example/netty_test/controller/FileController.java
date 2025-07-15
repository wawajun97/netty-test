package com.example.netty_test.controller;

import com.example.netty_test.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class FileController {
    private final FileService fileService;

    @GetMapping("/download")
    public ResponseEntity<?> downloadFile(@RequestParam Long id) {
        return fileService.downloadFile(id);
    }
}
