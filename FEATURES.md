# XNAT DICOM Router - Features Documentation

**Version:** 2.0.0
**Last Updated:** December 2024

## Overview

The XNAT DICOM Router is a standalone application for receiving, anonymizing, and routing DICOM data to XNAT instances, PACS systems, and file storage destinations.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         XNAT DICOM Router v2.0.0                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────────────┐  │
│  │ DICOM C-STORE│───▶│ FolderWatcher │───▶│    Processing Pipeline      │  │
│  │   Receiver   │    │   (per AE)    │    │ ┌────────────────────────┐  │  │
│  │   (dcm4che)  │    └──────────────┘    │ │   Anonymization        │  │  │
│  └──────────────┘                         │ │  - StreamingAnonymizer │  │  │
│        │                                  │ │  - ScriptEnhancer      │  │  │
│        │ Streaming                        │ │  - DicomEdit 6 Scripts │  │  │
│        │ Storage                          │ └────────────────────────┘  │  │
│        ▼                                  │ ┌────────────────────────┐  │  │
│  ┌──────────────┐                         │ │   Honest Broker        │  │  │
│  │   Incoming   │                         │ │  - CrosswalkStore      │  │  │
│  │   Directory  │                         │ │  - ID Mapping          │  │  │
│  └──────────────┘                         │ └────────────────────────┘  │  │
│                                           │ ┌────────────────────────┐  │  │
│                                           │ │   OCR PHI Detection    │  │  │
│                                           │ │  - Tesseract OCR       │  │  │
│                                           │ │  - alterPixels[]       │  │  │
│                                           │ └────────────────────────┘  │  │
│                                           └──────────────────────────────┘  │
│                                                          │                  │
│                                                          ▼                  │
│                              ┌───────────────────────────────────────────┐  │
│                              │           Destination Manager             │  │
│                              │  ┌─────────┐ ┌─────────┐ ┌─────────┐     │  │
│                              │  │  XNAT   │ │  DICOM  │ │  File   │     │  │
│                              │  │ Uploader│ │  C-STORE│ │ Storage │     │  │
│                              │  └─────────┘ └─────────┘ └─────────┘     │  │
│                              └───────────────────────────────────────────┘  │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                           Admin UI (React)                           │  │
│  │  Port 9090 - Routes, Destinations, Metrics, Transfers, Review, Audit │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Core Features

### 1. DICOM Reception (Streaming Storage)

- **Multiple AE Titles**: Configure separate routes per AE Title
- **High-Capacity**: Streaming file reception handles 2GB+ DICOM files
- **Transfer Syntaxes**: Supports all standard transfer syntaxes
- **Connection Pooling**: Handles concurrent transfers efficiently

**Key Files:**
- `DicomReceiver.java` - C-STORE SCP implementation with streaming storage
- `FolderWatcher.java` - Monitors incoming directories for complete studies

**Large File Handling:**
Files are streamed directly to disk using `PDVInputStream.copyTo()` instead of loading into memory, enabling reception of multi-gigabyte files (tested with 2.5GB Breast Tomosynthesis files).

### 2. Anonymization

#### DicomEdit 6 Script Processing

The router uses XNAT's DicomEdit 6 language for DICOM tag manipulation.

**Reference:** https://wiki.xnat.org/xnat-tools/dicomedit-6-language-reference

**Key Functions:**

| Function | Version | Description | Syntax |
|----------|---------|-------------|--------|
| `shiftDateTimeByIncrement` | 6.3+ | Shifts dates by N days | `tag := shiftDateTimeByIncrement[tag, "shift", "days"]` |
| `hashUID` | 6.0+ | SHA-1 based deterministic UID | `tag := hashUID[tag]` |
| `alterPixels` | 6.3+ | Pixel redaction | `alterPixels["rectangle", "l=x,t=y,r=x2,b=y2", "solid", "v=value"]` |
| `blankValues` | 6.6+ | Clear matching values | `blankValues[{pattern1, pattern2}]` |

#### ScriptEnhancer

Automatically enhances anonymization scripts with:

1. **Date Shifting**: Patient-specific offset applied to all date/time fields
   - Study Date, Series Date, Acquisition Date
   - Patient Birth Date
   - All other DA/DT/TM VR tags

2. **UID Hashing**: Consistent hash-based replacement
   - Study Instance UID
   - Series Instance UID
   - SOP Instance UID
   - Frame of Reference UID

**Key Files:**
- `ScriptEnhancer.java` - Enhances scripts with date shifting and UID hashing
- `StreamingAnonymizer.java` - Memory-efficient anonymization
- `LargeFileAnonymizer.java` - Chunk-based processing for large files
- `ScriptLibrary.java` - Built-in script management

### 3. Honest Broker Service

De-identification with ID mapping preservation:

- **CrosswalkStore**: SQLite-based patient ID crosswalk database
- **Naming Schemes**:
  - `hash` - SHA-256 based IDs
  - `adjective_animal` - Human-readable pseudonyms
  - `sequential` - Incrementing numeric IDs
- **Configurable Prefixes**: Patient ID and Name prefixes

**Key Files:**
- `HonestBrokerService.java` - Broker orchestration
- `LocalHonestBroker.java` - Local broker implementation
- `CrosswalkStore.java` - ID mapping persistence

### 4. OCR PHI Detection

Detects and redacts burned-in PHI using Tesseract OCR:

1. **Text Detection**: Extracts text regions with confidence scores
2. **PHI Classification**: Pattern matching for names, MRNs, dates
3. **Pixel Redaction**: Generates `alterPixels[]` commands

**Key Files:**
- `OcrService.java` - Tesseract integration
- `PhiPatternMatcher.java` - PHI pattern recognition
- `DicomOcrProcessor.java` - DICOM image processing

### 5. Destinations

#### XNAT Destinations
```yaml
destinations:
  production:
    type: xnat
    url: http://xnat.example.com
    username: user
    password: pass
    timeout: 120
    connection_pool_size: 10
```

#### DICOM AE Destinations
```yaml
destinations:
  pacs:
    type: dicom
    ae_title: PACS_AE
    host: pacs.example.com
    port: 104
    calling_ae_title: ROUTER
```

#### File Destinations
```yaml
destinations:
  backup:
    type: file
    path: /data/backup
    directory_pattern: "{StudyInstanceUID}"
```

### 6. Routing Configuration

```yaml
routes:
  - ae_title: ANON_RECV
    port: 11112
    destinations:
      - destination: production
        anonymize: true
        project_id: PROJECT001
        subject_prefix: SUBJ
        session_prefix: SESS
        use_honest_broker: true
        honest_broker: local-broker
        date_shift_days: -365
        hash_uids: true
```

### 7. Admin UI

React-based web interface on port 9090:

- **Dashboard**: System status and metrics
- **Routes**: Route configuration and status
- **Destinations**: Destination health monitoring
- **Transfers**: Active/completed transfer tracking
- **Review**: Manual review queue for flagged studies
- **Audit**: Anonymization audit trail
- **Logs**: Real-time log viewer

### 8. Metrics & Monitoring

- **Transfer Tracking**: Per-study, per-file tracking
- **Metrics Collection**: Throughput, latency, success rates
- **Health Checks**: Destination availability monitoring
- **Retry Management**: Automatic retry with backoff

## Configuration Reference

### Main Configuration (config.yaml)

```yaml
# Admin server
admin_port: 9090
admin_host: 0.0.0.0
admin_username: admin
admin_password: admin_secret
auth_required: true

# Directories
data_directory: ./data
scripts_directory: ./scripts

# Logging
log_level: INFO

# Destinations
destinations:
  # ... (see above)

# Routes
routes:
  # ... (see above)

# Honest Brokers
honest_brokers:
  local-broker:
    broker_type: local
    naming_scheme: adjective_animal
    patient_id_prefix: SUBJ
    cache_enabled: true

# Resilience
resilience:
  health_check_interval: 60
  max_retries: 10
  retry_delay: 300
  retention_days: 30
```

## CLI Commands

```bash
# Start the router
./start-router.sh start

# With custom config
java -jar dicom-router-2.0.0.jar start --config /path/to/config.yaml

# Stop
./start-router.sh stop
```

## Testing

### Sending DICOM Files

```bash
# Using dcmsend
dcmsend -v localhost 11112 \
  -aec ANON_TEST \
  /path/to/dicom/files/

# For large files, use default buffer settings
dcmsend localhost 11112 \
  -aec ANON_TEST \
  /path/to/large/file.dcm
```

### Unit Tests

```bash
# Run all tests
JAVA_HOME=/path/to/java18 ./gradlew test

# Run specific test class
./gradlew test --tests "io.xnatworks.router.anon.ScriptEnhancerTest"
```

### Test Coverage

| Component | Test File | Coverage |
|-----------|-----------|----------|
| AppConfig | AppConfigTest.java | YAML loading, saving, route finding |
| ScriptEnhancer | ScriptEnhancerTest.java | Date shifting, UID hashing |
| DestinationManager | DestinationManagerTest.java | Destination resolution |
| TransferTracker | TransferTrackerTest.java | Transfer state management |

## DicomEdit 6 Function Reference

### shiftDateTimeByIncrement (v6.3+)

Shifts date/time values by a specified amount:

```
(0008,0020) := shiftDateTimeByIncrement[(0008,0020), "-365", "days"]
```

- **Parameters**: tag, shift amount (string), unit ("days" or "seconds")
- **Preserves**: Timezone, precision
- **Negative values**: Shift into the past

### hashUID (v6.0+)

Generates deterministic SHA-1 based UIDs:

```
(0020,000D) := hashUID[(0020,000D)]
```

- **Deterministic**: Same input always produces same output
- **Valid DICOM UIDs**: Maintains UID format compliance

### alterPixels (v6.3+)

Redacts pixel regions:

```
alterPixels["rectangle", "l=100,t=50,r=300,b=100", "solid", "v=0"]
```

- **Shape**: "rectangle"
- **Parameters**: left, top, right, bottom
- **Fill**: "solid" with value

## Troubleshooting

### Large File Reception Fails

**Symptom**: "Peer aborted Association" error for large (>2GB) files

**Solution**: The router now uses streaming storage. Ensure you're running v2.0.0+.

### Date Shift Function Not Found

**Symptom**: "Failed to find function: dateInc"

**Solution**: Use `shiftDateTimeByIncrement` instead (DicomEdit 6 syntax):
```
tag := shiftDateTimeByIncrement[tag, "shift", "days"]
```

### Memory Issues

**Symptom**: OutOfMemoryError during anonymization

**Solution**:
1. Increase heap: `java -Xmx16g -jar dicom-router-2.0.0.jar`
2. Use StreamingAnonymizer for large files

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/status` | GET | Router status |
| `/api/routes` | GET | List routes |
| `/api/destinations` | GET | List destinations |
| `/api/transfers` | GET | List transfers |
| `/api/metrics` | GET | System metrics |
| `/api/scripts` | GET | List scripts |
| `/api/config` | GET/PUT | Configuration |

## Version History

### v2.0.0 (December 2024)
- Streaming DICOM file reception (2GB+ support)
- DicomEdit 6 shiftDateTimeByIncrement integration
- ScriptEnhancer for automatic date/UID processing
- Comprehensive test suite
- Large file anonymization support
