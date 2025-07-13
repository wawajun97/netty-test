package com.example.netty_test.service;

import com.example.netty_test.entity.FileEntity;
import com.example.netty_test.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class FileService {
    private final FileRepository fileRepository;

    @Value("${file.base.path}")
    private String FILE_BASE_PATH;

    public boolean insertFile(String fileName, byte[] fileData) throws IOException {
        FileEntity fileEntity = FileEntity.builder()
                .fileName(fileName)
                .fileDownloadUrl("")
                .build();

        if(!moveFile(fileEntity, fileData)) return false;

        fileRepository.save(fileEntity);

        return true;
    }

    private boolean moveFile(FileEntity fileEntity, byte[] fileData) throws IOException {
        log.info("fileName : {}", fileEntity.getFileName());
        BufferedOutputStream bs = null;
        try {
            String uuidFileName = makeUUidFileName(fileEntity.getFileName());
            String filePath = FILE_BASE_PATH + uuidFileName;

            log.info("uuidFileName : {}", uuidFileName);

            fileEntity.setFilePath(filePath);

            bs = new BufferedOutputStream(new FileOutputStream(filePath));
            bs.write(fileData);
        } catch (Exception e) {
            log.error("move file error : {}", e.getMessage());
            e.getStackTrace();
            return false;
        }finally {
            bs.close();
        }
        return true;
    }

    //파일명 중복 방지를 위해 uuid 파일명으로 저장
    private String makeUUidFileName(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf("."));

        UUID uuid = UUID.randomUUID();
        return uuid.toString() + extension;
    }
}
