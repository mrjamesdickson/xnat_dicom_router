# XNAT DICOM Router - Feature Roadmap

## Current Features (Implemented)

### Core Routing
- DICOM C-STORE SCP receiver with configurable AE titles and ports
- Multi-destination routing with filtering rules
- Honest Broker de-identification service integration
- Script-based DICOM tag modification
- Transfer status tracking and monitoring

### Import from Disk
- CLI command: `import --path /path/to/dicoms --route ROUTE_AE [--recursive] [--move]`
- Web UI: Import page with directory scanning, study preview, and job tracking
- Automatic DICOM file detection via magic bytes
- Study grouping by StudyInstanceUID
- Progress tracking with real-time updates

### Web Administration UI
- Dashboard with system status
- Route configuration management
- Destination management
- Broker configuration
- Script editor
- Transfer monitoring
- Log viewer
- Settings management

### Storage Page Features (Added 2024-12-13)
- File upload via drag-and-drop (files and folders)
- Archive extraction: ZIP, TAR, TAR.GZ
- Multi-file upload support
- "Move to Import" button for manual processing of uploaded studies
- Logs directory display with files and directories

### Theme Support (Added 2024-12-13)
- Four built-in themes: Light, Dark, High Contrast, Ocean
- Theme persisted to localStorage
- CSS variables for consistent theming
- Theme selector in header

### Route Configuration (Added 2024-12-13)
- `auto_import` option per route in config.yaml
- When enabled, uploaded files automatically processed and routed
- Example:
  ```yaml
  routes:
    - ae_title: MY_ROUTE
      port: 4104
      auto_import: true
      destinations:
        - destination: xnat_server
  ```

---

## Planned Features

### 1. Query/Retrieve Capabilities (DIMSE)

#### C-FIND Support
- Query remote PACS for studies/series/instances
- Configurable query parameters (patient ID, date range, modality, etc.)
- Results caching for performance
- Query profiles (saved queries)

#### C-MOVE Support
- Move studies from remote PACS to local storage
- Move to configured route for processing
- Batch move operations
- Progress tracking

#### C-GET Support
- Alternative to C-MOVE for firewalled environments
- Direct retrieval to local storage

### 2. DICOMweb Client (QIDO-RS/WADO-RS)

#### QIDO-RS (Query)
- RESTful queries to DICOMweb servers
- Support for study/series/instance queries
- Pagination handling
- OAuth2/Bearer token authentication

#### WADO-RS (Retrieve)
- Retrieve studies/series/instances
- Support for multipart/related responses
- Streaming for large datasets
- Resume interrupted downloads

#### STOW-RS (Store)
- Upload DICOM to DICOMweb endpoints
- Chunked uploads for large studies
- Error handling and retry logic

### 3. PostgreSQL Database Support

#### Configuration Storage
- Routes, destinations, brokers in PostgreSQL
- Version history for configurations
- Multi-instance deployment support

#### State Management
- Transfer history with full audit trail
- Job queues for async processing
- Metrics aggregation tables

#### Schema Design
```sql
-- Routes configuration
CREATE TABLE routes (
    id SERIAL PRIMARY KEY,
    ae_title VARCHAR(16) NOT NULL UNIQUE,
    port INTEGER NOT NULL,
    description TEXT,
    enabled BOOLEAN DEFAULT true,
    config JSONB,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Destinations
CREATE TABLE destinations (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    ae_title VARCHAR(16) NOT NULL,
    type VARCHAR(50) DEFAULT 'DIMSE',
    config JSONB,
    enabled BOOLEAN DEFAULT true
);

-- Transfer history
CREATE TABLE transfers (
    id SERIAL PRIMARY KEY,
    study_uid VARCHAR(64) NOT NULL,
    route_ae VARCHAR(16) NOT NULL,
    destination_id INTEGER REFERENCES destinations(id),
    status VARCHAR(50) NOT NULL,
    file_count INTEGER,
    total_bytes BIGINT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT,
    metadata JSONB
);

-- Metrics (time-series)
CREATE TABLE metrics (
    id SERIAL PRIMARY KEY,
    route_ae VARCHAR(16),
    metric_type VARCHAR(50) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    recorded_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_metrics_route_time ON metrics(route_ae, recorded_at);
```

### 4. DICOM Indexing Engine

#### Metadata Extraction
- Extract all standard DICOM tags
- Support for private tags (configurable)
- Pixel data statistics (optional)
- File hash for deduplication

#### Storage Schema
```sql
CREATE TABLE dicom_studies (
    id SERIAL PRIMARY KEY,
    study_uid VARCHAR(64) NOT NULL UNIQUE,
    patient_id VARCHAR(64),
    patient_name VARCHAR(255),
    study_date DATE,
    study_time TIME,
    accession_number VARCHAR(64),
    study_description TEXT,
    modalities VARCHAR(255)[],
    institution_name VARCHAR(255),
    referring_physician VARCHAR(255),
    series_count INTEGER,
    instance_count INTEGER,
    total_size BIGINT,
    source_type VARCHAR(50), -- filesystem, pacs, dicomweb
    source_id VARCHAR(255),
    indexed_at TIMESTAMP DEFAULT NOW(),
    metadata JSONB -- All extracted tags
);

CREATE TABLE dicom_series (
    id SERIAL PRIMARY KEY,
    series_uid VARCHAR(64) NOT NULL UNIQUE,
    study_id INTEGER REFERENCES dicom_studies(id),
    modality VARCHAR(16),
    series_number INTEGER,
    series_description TEXT,
    body_part VARCHAR(64),
    instance_count INTEGER,
    metadata JSONB
);

CREATE TABLE dicom_instances (
    id SERIAL PRIMARY KEY,
    sop_instance_uid VARCHAR(64) NOT NULL UNIQUE,
    series_id INTEGER REFERENCES dicom_series(id),
    sop_class_uid VARCHAR(64),
    instance_number INTEGER,
    file_path TEXT,
    file_size BIGINT,
    file_hash VARCHAR(64),
    metadata JSONB
);

-- Full-text search
CREATE INDEX idx_studies_fts ON dicom_studies
    USING gin(to_tsvector('english',
        coalesce(patient_name,'') || ' ' ||
        coalesce(study_description,'') || ' ' ||
        coalesce(institution_name,'')
    ));

-- JSONB indexes for flexible queries
CREATE INDEX idx_studies_metadata ON dicom_studies USING gin(metadata jsonb_path_ops);
CREATE INDEX idx_series_metadata ON dicom_series USING gin(metadata jsonb_path_ops);
```

### 5. Search API for ML Developers/Researchers

#### REST API Endpoints
```
GET /api/search/studies
    ?patient_id=...
    &study_date_from=...
    &study_date_to=...
    &modality=CT,MR
    &body_part=HEAD,CHEST
    &institution=...
    &description=*brain*
    &has_pixel_data=true
    &min_series=2
    &tags=0008,0060:CT;0018,0050:>0.5
    &limit=100
    &offset=0

GET /api/search/series
    ?study_uid=...
    &modality=...
    &body_part=...

GET /api/search/instances
    ?series_uid=...
    &sop_class=...

POST /api/search/advanced
    Content-Type: application/json
    {
      "query": {
        "bool": {
          "must": [
            {"match": {"modality": "CT"}},
            {"range": {"study_date": {"gte": "2023-01-01"}}}
          ],
          "should": [
            {"match": {"study_description": "brain"}}
          ],
          "filter": [
            {"term": {"institution_name": "Hospital A"}}
          ]
        }
      },
      "aggregations": {
        "by_modality": {"terms": {"field": "modality"}},
        "by_month": {"date_histogram": {"field": "study_date", "interval": "month"}}
      }
    }
```

#### Python SDK for Researchers
```python
from xnat_dicom_router import DicomSearch

client = DicomSearch("http://router:8080", api_key="...")

# Simple queries
studies = client.find_studies(
    modality=["CT", "MR"],
    study_date_from="2023-01-01",
    body_part="HEAD"
)

# Advanced queries
results = client.search({
    "query": {"match": {"study_description": "brain tumor"}},
    "aggregations": {"by_institution": {"terms": {"field": "institution_name"}}}
})

# Export to DataFrame
df = client.to_dataframe(studies)

# Download DICOM files
for study in studies[:10]:
    client.download_study(study.uid, "/data/downloads/")
```

### 6. Scheduled/Automated Pull Jobs

#### Job Configuration
```yaml
pull_jobs:
  - name: "Daily CT Sync"
    source:
      type: dimse
      host: pacs.hospital.org
      port: 104
      ae_title: PACS
    query:
      modality: CT
      study_date: TODAY
    schedule: "0 2 * * *"  # 2 AM daily
    destination:
      route: CT_PROCESSING
    options:
      delete_after_route: false

  - name: "Weekly Archive Sync"
    source:
      type: dicomweb
      url: https://archive.hospital.org/dicomweb
      auth:
        type: oauth2
        token_url: https://auth.hospital.org/token
        client_id: router
        client_secret: ${ARCHIVE_CLIENT_SECRET}
    query:
      StudyDate: "20230101-20231231"
      Modality: MR
    schedule: "0 0 * * 0"  # Sunday midnight
    destination:
      filesystem: /data/archive/mr
```

### 7. Data Source Connectors

#### Filesystem Connector
- Watch directories for new DICOM files
- Recursive scanning
- Pattern-based file filtering
- Automatic re-indexing on changes

#### PACS Connector (DIMSE)
- Periodic query for new studies
- Incremental sync based on study date
- Connection pooling
- Retry logic with backoff

#### DICOMweb Connector
- REST-based connectivity
- OAuth2/API key authentication
- Pagination handling
- Rate limiting

#### Cloud Storage Connector
- AWS S3 support
- Google Cloud Storage support
- Azure Blob Storage support
- Streaming for large files

### 8. Route Visualization Dashboard

#### Network Topology View
- Interactive diagram showing routes and destinations
- Connection status indicators (green/yellow/red)
- Click to drill down into route details
- Drag-and-drop route configuration

#### Real-time Activity
- Live transfer animations
- Active connection counts
- Queue depths
- Error indicators

#### Metrics Graphs
- Studies per hour/day/week
- Files transferred
- Bytes transferred
- Latency histograms
- Error rates
- Queue wait times

#### Historical Analytics
- Trend analysis
- Capacity planning metrics
- Peak usage identification
- Comparison across time periods

---

## Architecture Considerations

### Scalability
- Horizontal scaling with multiple router instances
- Shared PostgreSQL for coordination
- Redis for distributed caching (optional)
- Message queue for async processing (RabbitMQ/Kafka)

### High Availability
- Active-passive failover
- Health check endpoints
- Automatic reconnection
- Transaction logging for recovery

### Security
- TLS for all DICOM connections (DICOM TLS)
- OAuth2 for DICOMweb
- API key management
- Audit logging
- Role-based access control

### Monitoring
- Prometheus metrics endpoint
- Grafana dashboards (pre-built)
- Alerting rules
- Log aggregation (ELK stack)

---

## Implementation Priority

### Phase 1: Core Enhancements
1. PostgreSQL support for configuration
2. Basic metrics collection
3. Dashboard with graphs

### Phase 2: Query/Retrieve
1. DIMSE C-FIND/C-MOVE
2. DICOMweb QIDO-RS/WADO-RS
3. Query UI in web interface

### Phase 3: Indexing & Search
1. DICOM metadata indexing
2. Search API
3. Python SDK

### Phase 4: Automation
1. Scheduled pull jobs
2. Data source connectors
3. Advanced analytics

---

## Notes

This roadmap represents a comprehensive vision for evolving the XNAT DICOM Router into a full-featured enterprise DICOM routing and data management solution. Features should be prioritized based on user needs and feedback.

Generated: 2024-12-13
