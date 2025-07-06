package com.example.netty_test.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class ParseService {

    public void parseData(byte code, byte[] data) {
        switch(code) {
            case 0x00:
                log.info("String data : {}", new String(data, StandardCharsets.UTF_8));
                break;
            case 0x01:
                log.info("int data : {}", data);
                break;
        }
    }
}
