export interface TemplateCounts {
  sections: number;
  items: number;
  comments: number;
}

export interface ImportWarning {
  code: string;
  severity: "warning" | "info";
  message: string;
  sheet?: string | null;
  row?: number | null;
  column?: string | null;
  value?: string | null;
}

export interface ImportReport {
  sourceRows: number;
  importedRows: number;
  sourceColumns: string[];
  warnings: ImportWarning[];
  notes: string[];
}

export interface TemplateComment {
  id: string;
  name: string;
  position: number;
  type: string;
  contentHtml: string;
  previewHtml: string;
  originalHtml: string;
  sourceRow: number;
  sourceSheet: string;
  metadata: Record<string, string>;
}

export interface TemplateItem {
  id: string;
  name: string;
  position: number;
  comments: TemplateComment[];
}

export interface TemplateSection {
  id: string;
  name: string;
  position: number;
  items: TemplateItem[];
}

export interface TemplateSummary {
  id: string;
  name: string;
  version: number;
  sourceFileName: string;
  sourceSha256: string;
  createdAt: string;
  updatedAt: string;
  duplicateOf: string | null;
  counts: TemplateCounts;
  warningCount: number;
}

export interface TemplateDetail extends TemplateSummary {
  importReport: ImportReport;
  sections: TemplateSection[];
}

export interface WorkspaceCreated {
  token: string;
  workspaceId: string;
  templateId: string;
}

export interface TemplateUpdate {
  version: number;
  name: string;
  sections: {
    id: string;
    name: string;
    items: {
      id: string;
      name: string;
      comments: {
        id: string;
        name: string;
        contentHtml: string;
      }[];
    }[];
  }[];
}

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: string[];
}
