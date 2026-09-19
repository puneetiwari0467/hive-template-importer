package com.hiveimporter.parser;

import com.hiveimporter.api.ApiException;
import com.hiveimporter.api.ApiModels.ImportReport;
import com.hiveimporter.api.ApiModels.ImportWarning;
import com.hiveimporter.parser.ParsedWorkbook.ParsedComment;
import com.hiveimporter.parser.ParsedWorkbook.ParsedItem;
import com.hiveimporter.parser.ParsedWorkbook.ParsedSection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.CellValueRecordInterface;
import org.apache.poi.hssf.record.FormulaRecord;
import org.apache.poi.hssf.record.MulBlankRecord;
import org.apache.poi.hssf.record.MulRKRecord;
import org.apache.poi.hssf.record.RowRecord;
import org.apache.poi.hssf.record.SSTRecord;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.springframework.stereotype.Component;

@Component
public class SpectoraParser {
    public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    public static final int MAX_ROWS = 5000;
    public static final int MAX_COLUMNS = 256;
    public static final int MAX_CELLS = 200_000;
    public static final int MAX_CELL_CHARACTERS = 32_767;
    public static final int MAX_SOURCE_CHARACTERS = 8 * 1024 * 1024;
    public static final int MAX_METADATA_CHARACTERS = 16 * 1024 * 1024;
    private static final long MAX_ZIP_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 1024;
    private static final int MAX_SHEETS = 16;
    private static final int MAX_WARNINGS = 500;
    private static final String SECTION = "section name";
    private static final String ITEM = "item name";
    private static final String COMMENT = "comment name";
    private static final String TEXT = "comment text";
    private static final String TYPE = "comment type";
    private static final String ORDER = "order";
    private static final Set<String> REQUIRED = Set.of(SECTION, ITEM, COMMENT, TEXT, TYPE);
    private static final Set<String> INTERPRETED = Set.of(SECTION, ITEM, COMMENT, TEXT, TYPE, ORDER);
    private static final Set<String> KNOWN_METADATA = Set.of(
            "category (-1: low, 0: med, 1: high)",
            "multiple choice options (comma-separated)",
            "unit type options (numeric answers only, comma-separated)",
            "recommendation (from list)",
            "answer type (boolean, checkbox, date, number, range, text)",
            "default value", "default value 2 (for \"range\" types)",
            "default unit type (for \"number\" and \"range\" types)", "default location",
            "default estimate min", "default estimate max", "locked", "simple format",
            "disable photos", "uses", "last modified");
    private final PreviewRenderer previews;
    private final Semaphore parsingSlots = new Semaphore(2);

    public SpectoraParser(PreviewRenderer previews) {
        this.previews = previews;
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(MAX_ZIP_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_SOURCE_CHARACTERS);
    }

    public ParsedWorkbook parse(InputStream input, String sourceFileName) {
        if (!parsingSlots.tryAcquire()) {
            throw ApiException.limit("Two spreadsheets are already being parsed. Please retry shortly.");
        }
        try {
            byte[] bytes = input.readNBytes(MAX_UPLOAD_BYTES + 1);
            if (bytes.length > MAX_UPLOAD_BYTES) {
                throw ApiException.tooLarge("Spreadsheet exceeds the 10 MiB upload limit.");
            }
            String fileName = safeFileName(sourceFileName);
            if (bytes.length < 8) {
                throw ApiException.invalidExport("The file is empty or is not an Excel workbook.");
            }
            FileMagic magic = FileMagic.valueOf(bytes);
            if (magic != FileMagic.OOXML && magic != FileMagic.OLE2) {
                throw ApiException.invalidExport("Upload an Excel .xls or .xlsx Spectora export, not CSV, HTML or another file type.");
            }
            if (magic == FileMagic.OOXML) {
                checkArchive(bytes);
            } else {
                checkBiff(bytes);
            }
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                return readWorkbook(workbook, fileName, sha256(bytes), magic);
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (EncryptedDocumentException exception) {
            throw ApiException.invalidExport("Password-protected Excel files are unsupported. Export an unencrypted workbook.");
        } catch (IOException | RuntimeException exception) {
            throw ApiException.invalidExport("The workbook is damaged, unsupported or exceeds the archive safety limits.",
                    "Re-export the original Spectora template as an unencrypted Excel workbook and try again.");
        } finally {
            parsingSlots.release();
        }
    }

    private ParsedWorkbook readWorkbook(Workbook workbook, String fileName, String digest, FileMagic magic) {
        if (workbook.getNumberOfSheets() > MAX_SHEETS) {
            throw ApiException.invalidExport("Workbooks may contain at most 16 worksheets.");
        }
        var warnings = new WarningCollector();
        var notes = new ArrayList<String>();
        Sheet source = null;
        int physicalCells = 0;
        int sourceCharacters = 0;
        for (Sheet sheet : workbook) {
            if (sheet.getLastRowNum() >= MAX_ROWS) {
                throw ApiException.invalidExport("A worksheet exceeds the 5,000-row limit (including its header).",
                        "Worksheet: " + sheet.getSheetName());
            }
            boolean nonempty = false;
            for (Row row : sheet) {
                if (row.getLastCellNum() > MAX_COLUMNS) {
                    throw ApiException.invalidExport("A worksheet exceeds the 256-column limit.",
                            location(sheet, row.getRowNum() + 1, null));
                }
                for (Cell cell : row) {
                    if (++physicalCells > MAX_CELLS) {
                        throw ApiException.invalidExport("The workbook exceeds the 200,000-cell limit.");
                    }
                    String value = rawValue(cell, sheet.getSheetName());
                    sourceCharacters += value.length();
                    if (sourceCharacters > MAX_SOURCE_CHARACTERS) {
                        throw ApiException.invalidExport("The workbook exceeds the 8 MiB source-text limit.");
                    }
                    nonempty |= !value.isEmpty();
                }
            }
            if (nonempty) {
                if (source != null) {
                    throw ApiException.invalidExport("Multiple nonempty worksheets are unsupported; no rows were imported.",
                            "Keep one Spectora export sheet per upload. Found: " + source.getSheetName()
                                    + " and " + sheet.getSheetName());
                }
                source = sheet;
            } else {
                warnings.add("EMPTY_SHEET", "info", "An empty worksheet was ignored.",
                        sheet.getSheetName(), null, null, null);
            }
        }
        if (source == null) {
            throw ApiException.invalidExport("The workbook contains no nonempty worksheet.");
        }
        Row header = firstNonemptyRow(source);
        var headers = readHeaders(header, source);
        if (headers.size() > MAX_COLUMNS) {
            throw ApiException.invalidExport("The workbook exceeds the 256-column limit.");
        }
        var canonicalHeaders = new LinkedHashMap<String, Header>();
        for (Header column : headers) {
            if (canonicalHeaders.putIfAbsent(column.canonical(), column) != null) {
                throw ApiException.invalidExport("Ambiguous duplicate column headers are not supported.",
                        location(source, header.getRowNum() + 1, column.original()));
            }
        }
        var missing = REQUIRED.stream().filter(column -> !canonicalHeaders.containsKey(column)).sorted().toList();
        if (!missing.isEmpty()) {
            throw ApiException.invalidExport("This is not a supported Spectora comment export.",
                    "Missing required columns: " + String.join(", ", missing));
        }
        Header orderColumn = canonicalHeaders.get(ORDER);
        if (orderColumn == null) {
            warnings.add("ORDER_MISSING", "warning",
                    "There is no Order column; comments retain worksheet row order.",
                    source.getSheetName(), header.getRowNum() + 1, null, null);
        }
        for (Header column : headers) {
            if (!INTERPRETED.contains(column.canonical()) && !knownMetadata(column.canonical())) {
                warnings.add("UNKNOWN_COLUMN", "warning",
                        "This unrecognized field is retained verbatim in metadata but is not interpreted by the editor.",
                        source.getSheetName(), header.getRowNum() + 1, column.original(), null);
            }
        }
        if (headers.stream().anyMatch(column -> !INTERPRETED.contains(column.canonical()))) {
            warnings.add("PRESERVED_METADATA", "info",
                    "Category, answer types/defaults, options, units, estimates, recommendations, flags, usage, "
                            + "photo references/captions and timestamps are preserved in metadata when present, "
                            + "but are not interactive inspection controls. No referenced photos are downloaded.",
                    source.getSheetName(), null, null, null);
        }
        if (magic == FileMagic.OOXML && !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            warnings.add("SIGNATURE_DETECTED", "info",
                    "The file contains an OOXML (.xlsx) workbook. Its signature, not its extension, was used.",
                    source.getSheetName(), null, null, fileName);
        }
        if (workbook.isSheetHidden(workbook.getSheetIndex(source))
                || workbook.isSheetVeryHidden(workbook.getSheetIndex(source))) {
            warnings.add("HIDDEN_SHEET", "warning", "The source worksheet is hidden; its rows were still imported.",
                    source.getSheetName(), null, null, null);
        }
        if (source.getNumMergedRegions() > 0) {
            warnings.add("MERGED_CELLS", "warning",
                    "Merged-cell layout is not reproduced. Only actual cell values are imported; missing required names are rejected.",
                    source.getSheetName(), null, null, null);
        }
        var hierarchy = new LinkedHashMap<String, LinkedHashMap<String, List<SourceComment>>>();
        Set<Integer> namedIndexes = new HashSet<>();
        headers.forEach(column -> namedIndexes.add(column.index()));
        int importedRows = 0;
        int blankRows = 0;
        int lastSourceRow = header.getRowNum();
        int htmlRows = 0;
        long metadataCharacters = 0;
        for (int index = header.getRowNum() + 1; index <= source.getLastRowNum(); index++) {
            Row row = source.getRow(index);
            if (!hasValues(row, source.getSheetName())) {
                blankRows++;
                continue;
            }
            lastSourceRow = index;
            for (Cell cell : row) {
                if (!namedIndexes.contains(cell.getColumnIndex())
                        && !rawValue(cell, source.getSheetName()).isEmpty()) {
                    throw ApiException.invalidExport("A nonempty cell has no named header; no rows were imported.",
                            location(source, index + 1, "column " + (cell.getColumnIndex() + 1)));
                }
            }
            var values = new LinkedHashMap<String, String>();
            for (Header column : headers) {
                String value = rawValue(row.getCell(column.index()), source.getSheetName());
                metadataCharacters += column.original().length() + value.length();
                if (metadataCharacters > MAX_METADATA_CHARACTERS) {
                    throw ApiException.invalidExport("The expanded source metadata exceeds the 16 MiB text limit.");
                }
                values.put(column.original(), value);
            }
            String section = requiredName(values, canonicalHeaders.get(SECTION), source, index);
            String item = requiredName(values, canonicalHeaders.get(ITEM), source, index);
            String comment = requiredName(values, canonicalHeaders.get(COMMENT), source, index);
            String content = values.get(canonicalHeaders.get(TEXT).original());
            String type = values.get(canonicalHeaders.get(TYPE).original());
            if (type.length() > 128) {
                throw ApiException.invalidExport("Comment type exceeds 128 characters.",
                        location(source, index + 1, canonicalHeaders.get(TYPE).original()));
            }
            if (!Set.of("info", "limit", "defect").contains(type)) {
                warnings.add("COMMENT_TYPE", "warning",
                        "The comment type is not a standard info, limit or defect value; the original value is retained.",
                        source.getSheetName(), index + 1, canonicalHeaders.get(TYPE).original(), type);
            }
            var preview = previews.render(content);
            if (preview.htmlSource()) {
                htmlRows++;
            }
            if (preview.restricted()) {
                warnings.add("PREVIEW_SANITIZED", "warning",
                        "Unsafe or unsupported HTML was removed from the display-only preview. "
                                + "The editable source and immutable original are unchanged.",
                        source.getSheetName(), index + 1, canonicalHeaders.get(TEXT).original(), null);
            }
            BigDecimal order = null;
            if (orderColumn != null) {
                String rawOrder = values.get(orderColumn.original()).strip();
                if (!rawOrder.isEmpty() && rawOrder.length() <= 64) {
                    try {
                        order = new BigDecimal(rawOrder);
                    } catch (NumberFormatException ignored) {
                        // An entire item falls back to source-row order if any of its values is unusable.
                    }
                }
            }
            hierarchy.computeIfAbsent(section, key -> new LinkedHashMap<>())
                    .computeIfAbsent(item, key -> new ArrayList<>())
                    .add(new SourceComment(comment, type, content, preview.html(), index + 1,
                            source.getSheetName(), values, order));
            importedRows++;
        }
        if (importedRows == 0) {
            throw ApiException.invalidExport("The export contains a header but no comment rows.");
        }
        var sections = new ArrayList<ParsedSection>();
        for (var sectionEntry : hierarchy.entrySet()) {
            var items = new ArrayList<ParsedItem>();
            for (var itemEntry : sectionEntry.getValue().entrySet()) {
                List<SourceComment> comments = itemEntry.getValue();
                if (orderColumn != null && comments.stream().allMatch(comment -> comment.order() != null)) {
                    comments.sort(Comparator.comparing(SourceComment::order).thenComparingInt(SourceComment::sourceRow));
                } else if (orderColumn != null) {
                    warnings.add("ORDER_FALLBACK", "warning",
                            "An item has a blank or nonnumeric Order value. All comments in this item retain "
                                    + "worksheet row order instead of partially sorting it.",
                            source.getSheetName(), comments.getFirst().sourceRow(), orderColumn.original(), itemEntry.getKey());
                }
                var orderedComments = new ArrayList<ParsedComment>();
                for (SourceComment comment : comments) {
                    orderedComments.add(new ParsedComment(comment.name(), orderedComments.size(), comment.type(),
                            comment.content(), comment.preview(), comment.sourceRow(), comment.sheet(), comment.metadata()));
                }
                items.add(new ParsedItem(itemEntry.getKey(), items.size(), orderedComments));
            }
            sections.add(new ParsedSection(sectionEntry.getKey(), sections.size(), items));
        }
        if (blankRows > 0) {
            warnings.add("EMPTY_ROWS", "info", blankRows + " entirely empty worksheet row(s) were ignored.",
                    source.getSheetName(), null, null, null);
        }
        notes.add("Section and item positions follow first appearance in the worksheet; names are not trimmed or normalized.");
        notes.add("Comment positions use ascending numeric Order within each item, with source row as a stable tie-breaker. "
                + "If any Order in an item is blank/invalid, that entire item retains source-row order. Without Order, all comments retain row order.");
        notes.add("Positions are zero-based. Source row numbers are one-based spreadsheet rows including the header. "
                + "Source rows count the span after the header through the last nonempty data row; imported rows exclude empty rows.");
        notes.add("Every named source column, including empty values and unknown fields, is retained under its exact original header. "
                + "String values are preserved exactly. Numeric cells retain their locale-independent Excel number, "
                + "including raw date serials; boolean cells use TRUE/FALSE. Formula and error cells are rejected, never evaluated.");
        notes.add("The editor does not reproduce spreadsheet styles, hidden-row/column presentation, embedded workbook objects, "
                + "or rich-text cell runs. Row content remains available; referenced images are never fetched.");
        notes.add(htmlRows + " comment row(s) contain HTML. Previews keep basic text, lists, tables and safe absolute links; "
                + "scripts, CSS, event handlers, images, embedded media and unsafe/relative links are removed. "
                + "HTML may be normalized by the parser. Plain text is escaped with line breaks. Original source text is immutable.");
        if (warnings.omitted > 0) {
            notes.add(warnings.omitted + " additional repetitive warnings were omitted from the bounded report; all source metadata is retained.");
        }
        return new ParsedWorkbook(fileName, digest,
                new ImportReport(lastSourceRow - header.getRowNum(), importedRows,
                        headers.stream().map(Header::original).toList(), warnings.values, notes), sections);
    }

    private List<Header> readHeaders(Row header, Sheet sheet) {
        var result = new ArrayList<Header>();
        for (Cell cell : header) {
            String value = rawValue(cell, sheet.getSheetName());
            if (!value.isEmpty() && (value.isBlank() || value.length() > 256)) {
                throw ApiException.invalidExport("Column headers must contain 1–256 characters of visible text.",
                        location(sheet, header.getRowNum() + 1, "column " + (cell.getColumnIndex() + 1)));
            }
            if (!value.isBlank()) {
                if (cell.getCellType() != CellType.STRING) {
                    throw ApiException.invalidExport("Column headers must be text.",
                            location(sheet, header.getRowNum() + 1, "column " + (cell.getColumnIndex() + 1)));
                }
                result.add(new Header(cell.getColumnIndex(), value, canonical(value)));
            }
        }
        return result;
    }

    private static String canonical(String header) {
        String normalized = header.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "comment type (info, limit, defect)" -> TYPE;
            case "order (w/i item)" -> ORDER;
            default -> normalized;
        };
    }

    private static boolean knownMetadata(String name) {
        return KNOWN_METADATA.contains(name) || name.matches("default photo [0-9]+(?: caption)?");
    }

    private static String requiredName(Map<String, String> values, Header header, Sheet sheet, int rowIndex) {
        String value = values.get(header.original());
        if (value.isBlank() || value.length() > 512) {
            throw ApiException.invalidExport("Section, item and comment names must contain 1–512 characters.",
                    location(sheet, rowIndex + 1, header.original()));
        }
        return value;
    }

    private static Row firstNonemptyRow(Sheet sheet) {
        for (Row row : sheet) {
            if (hasValues(row, sheet.getSheetName())) {
                return row;
            }
        }
        throw ApiException.invalidExport("The worksheet is empty.");
    }

    private static boolean hasValues(Row row, String sheet) {
        if (row != null) {
            for (Cell cell : row) {
                if (!rawValue(cell, sheet).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String rawValue(Cell cell, String sheet) {
        if (cell == null) {
            return "";
        }
        String value = switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> NumberToTextConverter.toText(cell.getNumericCellValue());
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case BLANK, _NONE -> "";
            case FORMULA -> throw ApiException.invalidExport("Formula cells are unsupported and are never evaluated.",
                    "Worksheet " + sheet + ", row " + (cell.getRowIndex() + 1)
                            + ", column " + (cell.getColumnIndex() + 1) + ": replace the formula with an exported value.");
            case ERROR -> throw ApiException.invalidExport("The workbook contains an Excel error cell.",
                    "Worksheet " + sheet + ", row " + (cell.getRowIndex() + 1)
                            + ", column " + (cell.getColumnIndex() + 1) + ": correct the cell and re-export.");
        };
        if (value.length() > MAX_CELL_CHARACTERS) {
            throw ApiException.invalidExport("A cell exceeds the 32,767-character limit.",
                    "Worksheet " + sheet + ", row " + (cell.getRowIndex() + 1));
        }
        return value;
    }

    private static void checkArchive(byte[] bytes) throws IOException {
        long expanded = 0;
        int entries = 0;
        Set<String> names = new HashSet<>();
        boolean contentTypes = false;
        byte[] buffer = new byte[8192];
        var xmlBudget = new XmlBudget();
        var xmlFactory = XMLInputFactory.newFactory();
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xmlFactory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resources are unsupported");
        });
        try (var archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES || !names.add(entry.getName())) {
                    throw ApiException.invalidExport("The workbook archive has too many or duplicate entries.");
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains("../")) {
                    throw ApiException.invalidExport("The workbook archive contains an invalid entry path.");
                }
                if (name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) {
                    throw ApiException.invalidExport("Macro-enabled workbooks are unsupported; export a macro-free workbook.");
                }
                contentTypes |= "[Content_Types].xml".equals(name);
                var xml = name.endsWith(".xml") || name.endsWith(".rels") ? new ByteArrayOutputStream() : null;
                long entryBytes = 0;
                int read;
                while ((read = archive.read(buffer)) != -1) {
                    entryBytes += read;
                    expanded += read;
                    if (entryBytes > MAX_ZIP_ENTRY_BYTES || expanded > MAX_ZIP_BYTES) {
                        throw ApiException.invalidExport("The workbook archive exceeds the 16 MiB per-entry or 50 MiB expanded limit.");
                    }
                    if (xml != null) {
                        xml.write(buffer, 0, read);
                    }
                }
                if (entryBytes > 100_000 && entry.getCompressedSize() > 0
                        && (double) entry.getCompressedSize() / entryBytes < 0.01) {
                    throw ApiException.invalidExport("The workbook archive exceeds the safe 100:1 compression ratio.");
                }
                if (xml != null) {
                    checkXml(xml.toByteArray(), xmlFactory, xmlBudget);
                }
                archive.closeEntry();
            }
        }
        if (!contentTypes) {
            throw ApiException.invalidExport("The ZIP file is not an OOXML Excel workbook.");
        }
    }

    private static void checkXml(byte[] xml, XMLInputFactory factory, XmlBudget budget) {
        try {
            var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                int depth = 0;
                int currentRow = 0;
                int lastRow = 0;
                int lastColumn = 0;
                boolean worksheet = false;
                boolean insideRow = false;
                var rows = new HashSet<Integer>();
                var columns = new HashSet<Integer>();
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        throw ApiException.invalidExport("Workbook XML document entities are unsupported.");
                    }
                    if (event == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                        if ("row".equals(reader.getLocalName())) {
                            insideRow = false;
                        }
                    }
                    if (event != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    if (++depth > 64) {
                        throw ApiException.invalidExport("Workbook XML exceeds the 64-level nesting limit.");
                    }
                    String namespace = reader.getNamespaceURI();
                    if (!"http://schemas.openxmlformats.org/spreadsheetml/2006/main".equals(namespace)
                            && !"http://purl.oclc.org/ooxml/spreadsheetml/main".equals(namespace)) {
                        continue;
                    }
                    switch (reader.getLocalName()) {
                        case "worksheet" -> worksheet = true;
                        case "sheet" -> {
                            if (++budget.sheets > MAX_SHEETS) {
                                throw ApiException.invalidExport("Workbooks may contain at most 16 worksheets.");
                            }
                        }
                        case "si" -> {
                            if (++budget.strings > MAX_CELLS) {
                                throw ApiException.invalidExport("The workbook exceeds the 200,000 shared-string limit.");
                            }
                        }
                        case "xf" -> {
                            if (++budget.styles > 10_000) {
                                throw ApiException.invalidExport("The workbook exceeds the 10,000-style limit.");
                            }
                        }
                        case "row" -> {
                            if (worksheet) {
                                if (insideRow) {
                                    throw ApiException.invalidExport("The worksheet has improperly nested rows.");
                                }
                                insideRow = true;
                                String reference = reader.getAttributeValue(null, "r");
                                currentRow = reference == null ? lastRow + 1 : coordinateNumber(reference);
                                if (currentRow > MAX_ROWS) {
                                    throw ApiException.invalidExport("A worksheet exceeds the 5,000-row limit (including its header).");
                                }
                                if (!rows.add(currentRow)) {
                                    throw ApiException.invalidExport("The worksheet has duplicate row coordinates.");
                                }
                                lastRow = Math.max(lastRow, currentRow);
                                lastColumn = 0;
                                columns.clear();
                            }
                        }
                        case "c" -> {
                            if (worksheet) {
                                if (!insideRow) {
                                    throw ApiException.invalidExport("The worksheet contains a cell outside a data row.");
                                }
                                if (++budget.cells > MAX_CELLS) {
                                    throw ApiException.invalidExport("The workbook exceeds the 200,000-cell limit.");
                                }
                                String reference = reader.getAttributeValue(null, "r");
                                int column = lastColumn + 1;
                                if (reference != null) {
                                    int split = 0;
                                    column = 0;
                                    while (split < reference.length() && reference.charAt(split) >= 'A'
                                            && reference.charAt(split) <= 'Z') {
                                        column = column * 26 + reference.charAt(split++) - 'A' + 1;
                                        if (column > MAX_COLUMNS) {
                                            throw ApiException.invalidExport("A worksheet exceeds the 256-column limit.");
                                        }
                                    }
                                    if (split == 0 || coordinateNumber(reference.substring(split)) != currentRow) {
                                        throw ApiException.invalidExport("The worksheet has invalid or mismatched cell coordinates.");
                                    }
                                }
                                if (currentRow == 0 || column > MAX_COLUMNS || !columns.add(column)) {
                                    throw ApiException.invalidExport("The worksheet has duplicate, missing or out-of-range cell coordinates.");
                                }
                                lastColumn = column;
                            }
                        }
                        case "f" -> {
                            if (worksheet) {
                                throw ApiException.invalidExport("Formula cells are unsupported and are never evaluated.");
                            }
                        }
                        default -> { }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException exception) {
            throw ApiException.invalidExport("Workbook XML is malformed or references unsupported external resources.");
        }
    }

    private static int coordinateNumber(String value) {
        if (value.isEmpty() || value.length() > 9 || !value.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw ApiException.invalidExport("The worksheet has invalid row or cell coordinates.");
        }
        int number = Integer.parseInt(value);
        if (number < 1) {
            throw ApiException.invalidExport("Worksheet row coordinates must be positive.");
        }
        return number;
    }

    private static void checkBiff(byte[] bytes) throws IOException {
        // Event scanning bounds legacy binary workbooks before the heavier POI object model is allocated.
        try (var filesystem = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            int[] counts = {0, 0};
            var request = new HSSFRequest();
            request.addListenerForAllRecords(record -> {
                if (record instanceof BOFRecord bof && bof.getType() == BOFRecord.TYPE_WORKSHEET
                        && ++counts[1] > MAX_SHEETS) {
                    throw ApiException.invalidExport("Workbooks may contain at most 16 worksheets.");
                }
                if (record instanceof RowRecord row && row.getRowNumber() >= MAX_ROWS) {
                    throw ApiException.invalidExport("A worksheet exceeds the 5,000-row limit (including its header).");
                }
                if (record instanceof FormulaRecord) {
                    throw ApiException.invalidExport("Formula cells are unsupported and are never evaluated.");
                }
                if (record instanceof SSTRecord strings && strings.getNumUniqueStrings() > MAX_CELLS) {
                    throw ApiException.invalidExport("The workbook exceeds the 200,000 shared-string limit.");
                }
                if (record instanceof CellValueRecordInterface cell) {
                    biffCellBounds(cell.getRow(), cell.getColumn(), cell.getColumn(), counts);
                } else if (record instanceof MulBlankRecord blanks) {
                    biffCellBounds(blanks.getRow(), blanks.getFirstColumn(), blanks.getLastColumn(), counts);
                } else if (record instanceof MulRKRecord numbers) {
                    biffCellBounds(numbers.getRow(), numbers.getFirstColumn(), numbers.getLastColumn(), counts);
                }
            });
            new HSSFEventFactory().processWorkbookEvents(request, filesystem);
        }
    }

    private static void biffCellBounds(int row, int firstColumn, int lastColumn, int[] counts) {
        if (row < 0 || row >= MAX_ROWS) {
            throw ApiException.invalidExport("A worksheet exceeds the 5,000-row limit (including its header).");
        }
        if (firstColumn < 0 || lastColumn < firstColumn || lastColumn >= MAX_COLUMNS) {
            throw ApiException.invalidExport("A worksheet exceeds the 256-column limit.");
        }
        counts[0] += lastColumn - firstColumn + 1;
        if (counts[0] > MAX_CELLS) {
            throw ApiException.invalidExport("The workbook exceeds the 200,000-cell limit.");
        }
    }

    public static String safeFileName(String name) {
        String value = name == null ? "" : name.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        if (value.isBlank()) {
            value = "uploaded-template.xlsx";
        }
        if (value.length() > 255 || value.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidExport("The source filename must contain at most 255 characters and no control characters.");
        }
        return value;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static String location(Sheet sheet, int row, String column) {
        return "Worksheet " + sheet.getSheetName() + ", row " + row + (column == null ? "" : ", " + column);
    }

    private record Header(int index, String original, String canonical) {}

    private static final class XmlBudget {
        private int cells;
        private int sheets;
        private int strings;
        private int styles;
    }

    private record SourceComment(
            String name, String type, String content, String preview, int sourceRow,
            String sheet, Map<String, String> metadata, BigDecimal order) {}

    private static final class WarningCollector {
        private final List<ImportWarning> values = new ArrayList<>();
        private int omitted;

        void add(String code, String severity, String message, String sheet, Integer row, String column, String value) {
            if (values.size() < MAX_WARNINGS) {
                values.add(new ImportWarning(code, severity, message, sheet, row, column, value));
            } else {
                omitted++;
            }
        }
    }
}
