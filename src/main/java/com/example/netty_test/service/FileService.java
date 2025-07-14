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
import java.nio.file.Files;
import java.nio.file.Path;
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
                .build();

        //파일 저장 실패 시 return false
        if(!moveFile(fileEntity, fileData)) return false;

        //파일 저장 성공 시에만 db에 값 저장
        fileRepository.save(fileEntity);

        return true;
    }

    public void downloadFile() {

    }

    private boolean moveFile(FileEntity fileEntity, byte[] fileData) throws IOException {
        log.info("fileName : {}", fileEntity.getFileName());
        BufferedOutputStream bs = null;
        try {
            String uuidFileName = makeUUidFileName(fileEntity.getFileName());
            String filePath = FILE_BASE_PATH + uuidFileName;

            log.info("uuidFileName : {}", uuidFileName);

            fileEntity.setFilePath(filePath);

            // 디렉토리 경로 객체 생성
            Path directory = Path.of(FILE_BASE_PATH);

            // 디렉토리 존재 확인 후 없으면 생성
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }

            bs = new BufferedOutputStream(new FileOutputStream(filePath));
            bs.write(fileData);
        } catch (Exception e) {
            log.error("move file error : {}", e.getMessage());
            e.getStackTrace();
            return false;
        }finally {
            if(null != bs) bs.close();
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
