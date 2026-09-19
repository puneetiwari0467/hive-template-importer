package com.hiveimporter.service;

import com.hiveimporter.parser.ParsedWorkbook;
import com.hiveimporter.parser.SpectoraParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SampleWorkbook {
    public static final String FILE_NAME = "Room-by-Room Residential Template-2026-09-18.xls";
    private final byte[] bytes;
    private final ParsedWorkbook parsed;

    public SampleWorkbook(SpectoraParser parser) throws IOException {
        try (var input = new ClassPathResource("sample-data/" + FILE_NAME).getInputStream()) {
            bytes = input.readNBytes(SpectoraParser.MAX_UPLOAD_BYTES + 1);
        }
        parsed = parser.parse(new ByteArrayInputStream(bytes), FILE_NAME);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public ParsedWorkbook parsed() {
        return parsed;
    }
}
