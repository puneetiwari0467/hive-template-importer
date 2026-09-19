package com.hiveimporter.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hiveimporter.WorkbookFixtures;
import com.hiveimporter.api.ApiException;
import com.hiveimporter.api.ApiModels.TemplateCounts;
import com.hiveimporter.parser.ParsedWorkbook.ParsedComment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class SpectoraParserTest {
    private final SpectoraParser parser = new SpectoraParser(new PreviewRenderer());

    @Test
    void realFixturePreservesEveryNameCellContentMetadataAndStableOrder() throws Exception {
        byte[] fixture = WorkbookFixtures.sample();
        assertThat(fixture).hasSize(104892);
        assertThat(SpectoraParser.sha256(fixture))
                .isEqualTo("f0df035e04f688485080a16259486fdcbb1c1e06393281305df0634675cca406");
        var imported = parse(fixture, WorkbookFixtures.SAMPLE);
        assertThat(imported.counts()).isEqualTo(new TemplateCounts(22, 136, 798));
        assertThat(imported.report().sourceRows()).isEqualTo(798);
        assertThat(imported.report().importedRows()).isEqualTo(798);
        assertThat(imported.report().sourceColumns()).hasSize(42);
        assertThat(imported.sections()).extracting(ParsedWorkbook.ParsedSection::name).containsExactly(
                "Inspection Details", "Roof", "Exterior", "Basement, Crawlspace &amp; Structure", "Electrical", "Kitchen",
                "Master Bedroom", "Bedroom 2", "Bedroom 3", "Bedroom 4", "Bedroom 5", "Bedroom 6",
                "Bathroom 1", "Bathroom 2", "Bathroom 3", "Bathroom 4", "Living Room", "Laundry Room",
                "Utility Room", "Misc. Interior", "Attic", "Garage");
        var htmlComments = comments(imported).stream()
                .filter(comment -> new PreviewRenderer().render(comment.contentHtml()).htmlSource()).toList();
        assertThat(htmlComments).hasSize(365);
        assertThat(htmlComments.stream().map(ParsedComment::contentHtml).distinct()).hasSize(208);
        assertThat(imported.report().notes()).anyMatch(note -> note.startsWith("365 comment row(s) contain HTML."));
        assertThat(imported.report().warnings()).anyMatch(warning -> warning.code().equals("PRESERVED_METADATA"));
        assertThat(imported.report().warnings()).anyMatch(warning -> warning.code().equals("SIGNATURE_DETECTED"));
        assertThat(imported.report().warnings()).noneMatch(warning -> warning.code().equals("ORDER_FALLBACK"));

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(fixture))) {
            assertThat(workbook).isInstanceOf(XSSFWorkbook.class);
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(0);
            var expected = new LinkedHashMap<String, LinkedHashMap<String, List<ExpectedComment>>>();
            List<String> headers = new ArrayList<>();
            for (Cell cell : header) {
                headers.add(cell.getStringCellValue());
            }
            assertThat(imported.report().sourceColumns()).containsExactlyElementsOf(headers);
            for (int rowNumber = 1; rowNumber <= sheet.getLastRowNum(); rowNumber++) {
                var row = sheet.getRow(rowNumber);
                var values = new LinkedHashMap<String, String>();
                for (int column = 0; column < headers.size(); column++) {
                    values.put(headers.get(column), sourceValue(row.getCell(column)));
                }
                expected.computeIfAbsent(values.get("Section Name"), key -> new LinkedHashMap<>())
                        .computeIfAbsent(values.get("Item Name"), key -> new ArrayList<>())
                        .add(new ExpectedComment(rowNumber + 1, values));
            }
            Set<Integer> seenRows = new HashSet<>();
            assertThat(imported.sections()).extracting(ParsedWorkbook.ParsedSection::name)
                    .containsExactlyElementsOf(expected.keySet());
            for (int sectionPosition = 0; sectionPosition < imported.sections().size(); sectionPosition++) {
                var section = imported.sections().get(sectionPosition);
                assertThat(section.position()).isEqualTo(sectionPosition);
                var expectedItems = expected.get(section.name());
                assertThat(section.items()).extracting(ParsedWorkbook.ParsedItem::name)
                        .containsExactlyElementsOf(expectedItems.keySet());
                for (int itemPosition = 0; itemPosition < section.items().size(); itemPosition++) {
                    var item = section.items().get(itemPosition);
                    assertThat(item.position()).isEqualTo(itemPosition);
                    var expectedComments = expectedItems.get(item.name());
                    expectedComments.sort(Comparator
                            .comparing((ExpectedComment row) -> new BigDecimal(row.fields().get("Order (w/i item)")))
                            .thenComparingInt(ExpectedComment::row));
                    assertThat(item.comments()).hasSize(expectedComments.size());
                    for (int commentPosition = 0; commentPosition < item.comments().size(); commentPosition++) {
                        var comment = item.comments().get(commentPosition);
                        var expectedComment = expectedComments.get(commentPosition);
                        assertThat(comment.position()).isEqualTo(commentPosition);
                        assertThat(comment.sourceRow()).isEqualTo(expectedComment.row());
                        assertThat(comment.sourceSheet()).isEqualTo(sheet.getSheetName());
                        assertThat(comment.name()).isEqualTo(expectedComment.fields().get("Comment Name"));
                        assertThat(comment.type()).isEqualTo(expectedComment.fields().get("Comment Type (info, limit, defect)"));
                        assertThat(comment.contentHtml()).isEqualTo(expectedComment.fields().get("Comment Text"));
                        assertThat(comment.metadata()).containsExactlyInAnyOrderEntriesOf(expectedComment.fields());
                        assertThat(comment.metadata()).hasSize(42);
                        assertThat(seenRows.add(comment.sourceRow())).isTrue();
                    }
                }
            }
            assertThat(seenRows).hasSize(798).contains(2, 799);
        }
        assertThat(parse(fixture, WorkbookFixtures.SAMPLE)).isEqualTo(imported);
        assertThatThrownBy(() -> imported.sections().getFirst().items().getFirst().comments().getFirst()
                .metadata().put("Comment Text", "changed")).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void differentlyNamedWorkbookSupportsReorderedColumnsAliasesUnicodeAndUnknownMetadata(boolean legacy) throws Exception {
        try (Workbook workbook = legacy ? new HSSFWorkbook() : new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Another sheet Ω");
            WorkbookFixtures.row(sheet, 0, "Vendor extra 📎", "Comment Text", "Item Name", " Section Name ",
                    "Order", "Comment Name", "Comment Type", "Default Value");
            WorkbookFixtures.row(sheet, 1, "a,b\n  unchanged ", "Exact < 5 & text\nsecond", "Doors", " Basement ", 2, "Later", "info", "");
            WorkbookFixtures.row(sheet, 2, "é汉字🚪", "<p><b>Earlier</b></p>", "Doors", " Basement ", 1, "First tie", "defect", true);
            WorkbookFixtures.row(sheet, 3, "", "", "Doors", " Basement ", 1, "Second tie", "limit", 12.25);
            WorkbookFixtures.row(sheet, 4, "different", "Other room", "Doors", "Attic Ω", 0, "Another", "custom", "");
            var result = parse(WorkbookFixtures.bytes(workbook), legacy ? "different.xls" : "different.xlsx");
            assertThat(result.counts()).isEqualTo(new TemplateCounts(2, 2, 4));
            assertThat(result.sections()).extracting(ParsedWorkbook.ParsedSection::name).containsExactly(" Basement ", "Attic Ω");
            var comments = result.sections().getFirst().items().getFirst().comments();
            assertThat(comments).extracting(ParsedComment::name).containsExactly("First tie", "Second tie", "Later");
            assertThat(comments).extracting(ParsedComment::sourceRow).containsExactly(3, 4, 2);
            assertThat(comments.get(2).metadata().get("Vendor extra 📎")).isEqualTo("a,b\n  unchanged ");
            assertThat(comments.getFirst().metadata()).containsEntry("Default Value", "TRUE")
                    .containsEntry(" Section Name ", " Basement ");
            assertThat(comments.get(1).metadata()).containsEntry("Default Value", "12.25");
            assertThat(comments.get(2).previewHtml()).isEqualTo("Exact &lt; 5 &amp; text<br>\nsecond");
            assertThat(result.report().warnings()).anyMatch(warning ->
                    warning.code().equals("UNKNOWN_COLUMN") && "Vendor extra 📎".equals(warning.column()));
            assertThat(result.report().warnings()).anyMatch(warning -> warning.code().equals("COMMENT_TYPE"));
        }
    }

    @Test
    void blankRowsHeaderOffsetAndEmptySheetsAreExplicit() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Export");
            WorkbookFixtures.row(sheet, 2, WorkbookFixtures.HEADERS.toArray());
            WorkbookFixtures.row(sheet, 3, " Room ", " Item ", " Name ", " \r\n ", "info", 1);
            sheet.createRow(4);
            WorkbookFixtures.row(sheet, 5, " Room ", " Item ", "Next", "", "defect", 2);
            workbook.createSheet("Empty");
            var result = parse(WorkbookFixtures.bytes(workbook));
            assertThat(result.report().sourceRows()).isEqualTo(3);
            assertThat(result.report().importedRows()).isEqualTo(2);
            assertThat(result.sections().getFirst().items().getFirst().comments())
                    .extracting(ParsedComment::sourceRow).containsExactly(4, 6);
            assertThat(result.sections().getFirst().items().getFirst().comments().getFirst().contentHtml()).isEqualTo(" \r\n ");
            assertThat(result.report().warnings()).extracting(warning -> warning.code())
                    .contains("EMPTY_SHEET", "EMPTY_ROWS");
        }
    }

    @Test
    void dateFormattedNumbersRetainUnderlyingExcelSerialAndBooleansRetainTheirValue() throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            var sheet = workbook.getSheetAt(0);
            sheet.getRow(0).createCell(6).setCellValue("Custom date");
            sheet.getRow(0).createCell(7).setCellValue("Locked");
            var cell = sheet.getRow(1).createCell(6);
            cell.setCellValue(45200.25);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            cell.setCellStyle(style);
            sheet.getRow(1).createCell(7).setCellValue(false);
            var result = parse(WorkbookFixtures.bytes(workbook));
            var comment = comments(result).stream().filter(value -> value.sourceRow() == 2).findFirst().orElseThrow();
            assertThat(comment.metadata()).containsEntry("Custom date", "45200.25").containsEntry("Locked", "FALSE");
            assertThat(result.report().notes()).anyMatch(note -> note.contains("raw date serials"));
        }
    }

    @Test
    void missingOrderAndPartlyInvalidOrderKeepWholeItemInSourceOrder() throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            var sheet = workbook.getSheetAt(0);
            sheet.getRow(1).getCell(5).setCellValue("not a number");
            var invalidOrder = parse(WorkbookFixtures.bytes(workbook));
            assertThat(invalidOrder.sections().getFirst().items().getFirst().comments())
                    .extracting(ParsedComment::sourceRow).containsExactly(2, 3);
            assertThat(invalidOrder.report().warnings()).anyMatch(warning -> warning.code().equals("ORDER_FALLBACK"));
            for (var row : sheet) {
                row.removeCell(row.getCell(5));
            }
            var missingOrder = parse(WorkbookFixtures.bytes(workbook));
            assertThat(missingOrder.sections().getFirst().items().getFirst().comments())
                    .extracting(ParsedComment::sourceRow).containsExactly(2, 3);
            assertThat(missingOrder.report().warnings()).anyMatch(warning -> warning.code().equals("ORDER_MISSING"));
        }
    }

    @Test
    void decimalNegativeAndTiedOrdersSortNumericallyNotLexicographically() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet();
            WorkbookFixtures.row(sheet, 0, WorkbookFixtures.HEADERS.toArray());
            String[] orders = {"10", "2", "-1.25", "2", "1e2", "0"};
            for (int index = 0; index < orders.length; index++) {
                WorkbookFixtures.row(sheet, index + 1, "S", "I", "Name " + index, "", "info", orders[index]);
            }
            var result = parse(WorkbookFixtures.bytes(workbook));
            assertThat(comments(result)).extracting(ParsedComment::sourceRow).containsExactly(4, 7, 3, 5, 2, 6);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a workbook", "<html>not an xls</html>", "Section Name,Item Name,Comment Name,Comment Text"})
    void arbitraryOrEmptyInputIsRejected(String input) {
        invalid(input.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void truncatedRealWorkbookIsRejected() throws Exception {
        byte[] sample = WorkbookFixtures.sample();
        invalid(java.util.Arrays.copyOf(sample, sample.length / 2));
    }

    @Test
    void oversizedUploadIsRejectedBeforeWorkbookParsing() {
        assertThatThrownBy(() -> parse(new byte[SpectoraParser.MAX_UPLOAD_BYTES + 1]))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 6})
    void formulasInNamesContentOrUnknownMetadataAreRejectedWithoutEvaluation(int column) throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            var sheet = workbook.getSheetAt(0);
            if (column == 6) {
                sheet.getRow(0).createCell(column).setCellValue("Unrecognized field");
                sheet.getRow(1).createCell(column);
            }
            sheet.getRow(1).getCell(column).setCellFormula("1+1");
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(error.getMessage()).contains("Formula").contains("never evaluated");
                    });
        }
    }

    @Test
    void excelErrorCellsAreExplicitlyRejected() throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            workbook.getSheetAt(0).getRow(1).getCell(3).setCellErrorValue(FormulaError.DIV0.getCode());
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("error cell");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Section Name", " section name ", "Comment Type", "Order"})
    void duplicateAndAliasAmbiguousHeadersAreRejected(String ambiguous) throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            workbook.getSheetAt(0).getRow(0).createCell(6).setCellValue(ambiguous);
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("Ambiguous");
        }
    }

    @Test
    void nonmatchingHeadersUnnamedDataAndMissingNamesAreRejected() throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            var sheet = workbook.getSheetAt(0);
            sheet.getRow(0).getCell(0).setCellValue("Not a section header");
            invalid(WorkbookFixtures.bytes(workbook));
            sheet.getRow(0).getCell(0).setCellValue("Section Name");
            sheet.getRow(1).createCell(10).setCellValue("Would be silently lost");
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("no named header");
            sheet.getRow(1).removeCell(sheet.getRow(1).getCell(10));
            sheet.getRow(1).getCell(0).setCellValue(" \n");
            invalid(WorkbookFixtures.bytes(workbook));
        }
    }

    @Test
    void emptyWorkbookAndHeaderOnlyWorkbookAreRejected() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet();
            invalid(WorkbookFixtures.bytes(workbook));
            WorkbookFixtures.row(sheet, 0, WorkbookFixtures.HEADERS.toArray());
            invalid(WorkbookFixtures.bytes(workbook));
        }
    }

    @Test
    void additionalNonemptySheetsAreRejectedEvenWhenHiddenOrNotSpectora() throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            var extra = workbook.createSheet("Do not silently discard");
            WorkbookFixtures.row(extra, 0, "An unrelated note");
            workbook.setSheetHidden(1, true);
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("Multiple nonempty worksheets");
        }
    }

    @Test
    void excessiveWorksheetRowAndColumnCoordinatesAreRejected() throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            var sheet = workbook.getSheetAt(0);
            WorkbookFixtures.row(sheet, SpectoraParser.MAX_ROWS, "Too far");
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("5,000-row");
            sheet.removeRow(sheet.getRow(SpectoraParser.MAX_ROWS));
            sheet.getRow(1).createCell(SpectoraParser.MAX_COLUMNS).setCellValue("Too wide");
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("256-column");
        }
    }

    @Test
    void independentCellCountLimitRejectsAnOtherwiseBoundedBiffWorkbook() throws Exception {
        try (var workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet();
            for (int row = 0; row < 782; row++) {
                var cells = sheet.createRow(row);
                for (int column = 0; column < 256; column++) {
                    cells.createCell(column);
                }
            }
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("200,000-cell");
        }
    }

    @Test
    void totalSourceTextLimitCountsRepeatedSharedStrings() throws Exception {
        try (var workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet();
            WorkbookFixtures.row(sheet, 0, WorkbookFixtures.HEADERS.toArray());
            String repeated = "x".repeat(32767);
            for (int row = 1; row < 270; row++) {
                WorkbookFixtures.row(sheet, row, "S", "I", "C", repeated, "info", row);
            }
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("source-text limit");
        }
    }

    @Test
    void excessiveSheetCountIsRejectedEvenForEmptySheets() throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            for (int index = 0; index < 16; index++) {
                workbook.createSheet("Empty " + index);
            }
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("16 worksheets");
        }
    }

    @Test
    void oversizedAndWhitespaceOnlyHeadersAreRejected() throws Exception {
        try (var workbook = WorkbookFixtures.simple(false)) {
            var header = workbook.getSheetAt(0).getRow(0).createCell(6);
            header.setCellValue(" ".repeat(2));
            invalid(WorkbookFixtures.bytes(workbook));
            header.setCellValue("x".repeat(257));
            assertThatThrownBy(() -> parse(WorkbookFixtures.bytes(workbook)))
                    .isInstanceOf(ApiException.class).hasMessageContaining("1–256");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-ooxml", "ratio", "expanded-entry", "entry-count", "macro", "path"})
    void archiveSafetyBoundsAndUnsupportedContainersAreRejected(String kind) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            if (!kind.equals("not-ooxml")) {
                zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
                zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            switch (kind) {
                case "entry-count" -> {
                    for (int index = 0; index < 1024; index++) {
                        zip.putNextEntry(new ZipEntry("entry-" + index));
                        zip.closeEntry();
                    }
                }
                case "macro" -> {
                    zip.putNextEntry(new ZipEntry("xl/vbaProject.bin"));
                    zip.write(1);
                    zip.closeEntry();
                }
                case "path" -> {
                    zip.putNextEntry(new ZipEntry("../unexpected.xml"));
                    zip.write(1);
                    zip.closeEntry();
                }
                default -> {
                    zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
                    int size = switch (kind) {
                        case "ratio" -> 1024 * 1024;
                        case "expanded-entry" -> 16 * 1024 * 1024 + 1;
                        default -> 10;
                    };
                    byte[] buffer = new byte[8192];
                    while (size > 0) {
                        int write = Math.min(size, buffer.length);
                        zip.write(buffer, 0, write);
                        size -= write;
                    }
                    zip.closeEntry();
                }
            }
        }
        invalid(bytes.toByteArray());
    }

    private ParsedWorkbook parse(byte[] input) {
        return parse(input, "test.xlsx");
    }

    private ParsedWorkbook parse(byte[] input, String filename) {
        return parser.parse(new ByteArrayInputStream(input), filename);
    }

    private void invalid(byte[] input) {
        assertThatThrownBy(() -> parse(input)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private static List<ParsedComment> comments(ParsedWorkbook workbook) {
        return workbook.sections().stream().flatMap(section -> section.items().stream())
                .flatMap(item -> item.comments().stream()).toList();
    }

    private static String sourceValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> NumberToTextConverter.toText(cell.getNumericCellValue());
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case BLANK, _NONE -> "";
            default -> throw new AssertionError("The real fixture unexpectedly contains formula or error cells");
        };
    }

    private record ExpectedComment(int row, Map<String, String> fields) {}
}
