package com.hiveimporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class WorkbookFixtures {
    public static final String SAMPLE = "Room-by-Room Residential Template-2026-09-18.xls";
    public static final List<String> HEADERS = List.of(
            "Section Name", "Item Name", "Comment Name", "Comment Text",
            "Comment Type (info, limit, defect)", "Order (w/i item)");

    private WorkbookFixtures() {}

    public static byte[] sample() throws IOException {
        try (InputStream input = WorkbookFixtures.class.getResourceAsStream("/sample-data/" + SAMPLE)) {
            if (input == null) {
                throw new IOException("Bundled sample is missing");
            }
            return input.readAllBytes();
        }
    }

    public static Workbook simple(boolean legacy) {
        Workbook workbook = legacy ? new HSSFWorkbook() : new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Different export");
        row(sheet, 0, HEADERS.toArray());
        row(sheet, 1, "Basement Ω", "Doors", "First observation", "Plain < 5 & exact\nnext line", "info", 2);
        row(sheet, 2, "Basement Ω", "Doors", "Earlier observation", "<p><b>Safe HTML</b></p>", "defect", 1);
        row(sheet, 3, "Attic 🚪", "Doors", "Other room", "", "limit", 0);
        return workbook;
    }

    public static byte[] alternate() throws IOException {
        try (Workbook workbook = simple(false)) {
            return bytes(workbook);
        }
    }

    public static void row(Sheet sheet, int rowNumber, Object... values) {
        var row = sheet.createRow(rowNumber);
        for (int index = 0; index < values.length; index++) {
            Cell cell = row.createCell(index);
            Object value = values[index];
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else if (value instanceof Boolean bool) {
                cell.setCellValue(bool);
            } else if (value != null) {
                cell.setCellValue(value.toString());
            }
        }
    }

    public static byte[] bytes(Workbook workbook) throws IOException {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
