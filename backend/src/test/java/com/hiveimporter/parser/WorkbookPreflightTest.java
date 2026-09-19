package com.hiveimporter.parser;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hiveimporter.WorkbookFixtures;
import com.hiveimporter.api.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class WorkbookPreflightTest {
    private final SpectoraParser parser = new SpectoraParser(new PreviewRenderer());

    @Test
    void duplicateXmlRowsCannotSilentlyOverwriteSourceDataInPoi() throws Exception {
        byte[] duplicate = rewriteSheet(text -> text.replace("</sheetData>",
                "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Lost value</t></is></c></row></sheetData>"));
        assertThatThrownBy(() -> parse(duplicate)).isInstanceOf(ApiException.class).hasMessageContaining("duplicate row");
    }

    @Test
    void duplicateXmlCellsCannotSilentlyOverwriteSourceDataInPoi() throws Exception {
        byte[] duplicate = rewriteSheet(text -> text.replaceFirst("</row>",
                "<c r=\"A1\" t=\"inlineStr\"><is><t>Lost header</t></is></c></row>"));
        assertThatThrownBy(() -> parse(duplicate)).isInstanceOf(ApiException.class).hasMessageContaining("duplicate");
    }

    @Test
    void documentTypeAndEntityReferencesAreRejectedBeforePoiLoadsTheArchive() throws Exception {
        byte[] unsafe = rewriteSheet(text -> text.replaceFirst("\\?>",
                "?><!DOCTYPE worksheet [<!ENTITY external SYSTEM \"https://example.invalid/no-fetch\">]>"));
        assertThatThrownBy(() -> parse(unsafe)).isInstanceOf(ApiException.class).hasMessageContaining("entities");
    }

    @Test
    void excessiveXmlNestingIsRejectedBeforeObjectModelAllocation() throws Exception {
        byte[] nested = rewriteSheet(text -> text.replace("</worksheet>",
                "<nested>".repeat(65) + "</nested>".repeat(65) + "</worksheet>"));
        assertThatThrownBy(() -> parse(nested)).isInstanceOf(ApiException.class).hasMessageContaining("nesting limit");
    }

    @Test
    void unsupportedBinaryRowCoordinatesAreRejectedByEventPreflight() throws Exception {
        try (var workbook = WorkbookFixtures.simple(true)) {
            WorkbookFixtures.row(workbook.getSheetAt(0), 5000, "Outside allowed rows");
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("5,000-row");
        }
    }

    private void parse(byte[] bytes) {
        parser.parse(new ByteArrayInputStream(bytes), "fixture.xls");
    }

    private static byte[] rewriteSheet(UnaryOperator<String> rewrite) throws Exception {
        byte[] bytes = WorkbookFixtures.alternate();
        var result = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes));
                var output = new ZipOutputStream(result)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                byte[] value = input.readAllBytes();
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    value = rewrite.apply(new String(value, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                }
                output.write(value);
                output.closeEntry();
                input.closeEntry();
            }
        }
        return result.toByteArray();
    }
}
