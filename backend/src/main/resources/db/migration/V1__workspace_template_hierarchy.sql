CREATE TABLE workspaces (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_workspaces_token_hash UNIQUE (token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE demo_quota_guard (
    id TINYINT NOT NULL PRIMARY KEY,
    CONSTRAINT chk_demo_guard CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO demo_quota_guard (id) VALUES (1);

CREATE TABLE templates (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    source_file_name VARCHAR(255) NOT NULL,
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    duplicate_of CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    section_count INT NOT NULL,
    item_count INT NOT NULL,
    comment_count INT NOT NULL,
    warning_count INT NOT NULL,
    import_report JSON NOT NULL,
    CONSTRAINT fk_templates_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
    CONSTRAINT fk_templates_original FOREIGN KEY (duplicate_of) REFERENCES templates (id) ON DELETE SET NULL,
    CONSTRAINT chk_template_version CHECK (version >= 1),
    CONSTRAINT chk_template_counts CHECK (section_count > 0 AND item_count > 0 AND comment_count > 0 AND warning_count >= 0),
    INDEX idx_templates_workspace_created (workspace_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE template_sections (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    template_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(512) NOT NULL,
    position INT NOT NULL,
    CONSTRAINT fk_sections_template FOREIGN KEY (template_id) REFERENCES templates (id) ON DELETE CASCADE,
    CONSTRAINT uq_sections_position UNIQUE (template_id, position),
    CONSTRAINT chk_sections_position CHECK (position >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE template_items (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    section_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(512) NOT NULL,
    position INT NOT NULL,
    CONSTRAINT fk_items_section FOREIGN KEY (section_id) REFERENCES template_sections (id) ON DELETE CASCADE,
    CONSTRAINT uq_items_position UNIQUE (section_id, position),
    CONSTRAINT chk_items_position CHECK (position >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE template_comments (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(512) NOT NULL,
    position INT NOT NULL,
    comment_type VARCHAR(128) NOT NULL,
    content_html MEDIUMTEXT NOT NULL,
    preview_html MEDIUMTEXT NOT NULL,
    original_html MEDIUMTEXT NOT NULL,
    source_row INT NOT NULL,
    source_sheet VARCHAR(255) NOT NULL,
    metadata JSON NOT NULL,
    CONSTRAINT fk_comments_item FOREIGN KEY (item_id) REFERENCES template_items (id) ON DELETE CASCADE,
    CONSTRAINT uq_comments_position UNIQUE (item_id, position),
    CONSTRAINT chk_comments_position CHECK (position >= 0),
    CONSTRAINT chk_comments_source_row CHECK (source_row > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
