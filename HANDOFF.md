# HANDOFF.md - Crosswalk-Based UID Matching for Review UI

## 1. Context Summary

**Project:** XNAT DICOM Router (`/Users/james/projects/xnat_dicom_router/java-app`)

**Original Goal:** When `hashUids` is enabled in an Honest Broker configuration, the Review UI needs to use the crosswalk database to match original DICOM files with their anonymized counterparts (since the SOP Instance UIDs are hashed and no longer match directly).

**Why:** Without crosswalk lookup, when UID hashing is enabled, the Review UI cannot correlate original and anonymized files because the hashed UIDs in anonymized files don't match the original UIDs. The crosswalk stores these mappings (original UID → hashed UID).

## 2. Current State

**Status: IMPLEMENTATION COMPLETE AND TESTED**

### Files Modified:

1. **`src/main/java/io/xnatworks/router/archive/ArchiveManager.java`**
   - Added `honestBrokerName` and `hashUidsEnabled` fields to `ArchiveMetadata` inner class
   - Added getters/setters for these fields
   - Added `updateBrokerInfo(aeTitle, studyUid, brokerName, hashUidsEnabled)` method
   - Added overloaded `archiveAnonymized()` method that includes broker info

2. **`src/main/java/io/xnatworks/router/review/DicomComparisonService.java`**
   - Added `CrosswalkStore` field and updated constructor
   - Updated `getScanComparisons()` with 4-tier matching strategy:
     1. Crosswalk lookup (for hashUids=true) - most accurate
     2. Filename matching
     3. Internal SOP UID matching (for non-hashed UIDs)
     4. Instance number fallback (least reliable)
   - **[2025-12-22] Fixed `getTagValue()` method to show actual DICOM values instead of "[binary: X bytes]"**
     - Now properly uses `attrs.getString()` and `attrs.getStrings()` for VR-specific conversions
     - Falls back to UTF-8 decoding for byte arrays when possible
     - Only shows "[binary: X bytes]" for truly binary data

3. **`src/main/java/io/xnatworks/router/api/AdminServer.java`**
   - Updated `DicomComparisonService` instantiation to pass `CrosswalkStore` from `HonestBrokerService`

4. **`src/main/java/io/xnatworks/router/DicomRouter.java`**
   - Added call to `archiveManager.updateBrokerInfo()` after dual-write archiving (line 774-783)
   - This stores the broker name and hashUids setting in the archive metadata

### Test Results (Verified Working):

**Archive Metadata** (`java-app/data/ANON_TEST/archive/2025-12-21/study_crosswalk_test/archive_metadata.json`):
```json
{
  "studyUid" : "crosswalk_test",
  "aeTitle" : "ANON_TEST",
  "callingAeTitle" : "FOLDER_WATCHER",
  "archivedAt" : "2025-12-21T23:07:17.119102",
  "honestBrokerName" : "my-hashuid-dateshift-hash-naming",
  "hashUidsEnabled" : true
}
```

**Crosswalk Database Entries** (verified via SQLite):
| Original SOP UID | Hashed SOP UID | Type |
|-----------------|----------------|------|
| `1.3.6.1.4.1.14519.5.2.1.338872345541366980...` | `2.25.53867774742225158964857284819648229641` | sop_uid |
| `1.3.6.1.4.1.14519.5.2.1.108466716213459599...` | `2.25.196327475848472621147580860879880054469` | sop_uid |

**Header Comparison API** (now shows actual values):
```json
{
  "tag": "(0008,0018)",
  "name": "SOPInstanceUID",
  "originalValue": "1.3.6.1.4.1.14519.5.2.1.108466716213459599996954749662079097073",
  "anonymizedValue": "2.25.196327475848472621147580860879880054469",
  "changed": true
},
{
  "tag": "(0010,0010)",
  "name": "PatientName",
  "originalValue": "UPENN-GBM-00006",
  "anonymizedValue": "ANONYMOUS",
  "changed": true,
  "isPhi": true
}
```

### Key Decisions:
- Archive metadata stores broker info so Review UI can look up the correct crosswalk
- 4-tier matching strategy ensures graceful fallback for various scenarios
- Crosswalk lookup only attempted when hashUidsEnabled=true AND brokerName is set

## 3. Next Steps

1. **Test the Review UI in browser:**
   - Router is currently running on `http://localhost:9090`
   - Login with username: `admin`, password: `admin123`
   - Navigate to the Review section
   - Select the `crosswalk_test` study from `ANON_TEST` route
   - Verify the header comparison shows actual values (not "[binary: X bytes]")

2. **Burned-in PHI Testing:**
   - The file `/Users/james/projects/data/dicom_with_burned_in_text.dcm` appears to be truncated/corrupt (18KB is too small for a PET image)
   - OCR functionality works correctly on valid DICOM files (tested on MR images)
   - Need to find or create a valid DICOM file with actual burned-in text to test OCR

3. **OCR Environment Setup:**
   - OCR requires Tesseract native library
   - Start router with proper environment:
   ```bash
   TESSDATA_PREFIX=/usr/local/share/tessdata \
   /Users/james/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home/bin/java \
     -Djna.library.path=/usr/local/lib \
     -Djava.library.path=/usr/local/lib \
     -Xms2g -Xmx16g -XX:+UseG1GC \
     -jar java-app/build/libs/dicom-router-2.1.0.jar \
     --config config.yaml start --admin-port 9090
   ```

## 4. Key Information

### Important Classes:
- `CrosswalkStore` - SQLite-based storage for UID mappings (`ID_TYPE_SOP_UID = "sop_uid"`)
- `DicomComparisonService` - Handles Review UI file comparisons
- `ArchiveManager.ArchiveMetadata` - JSON metadata stored with each archived study

### API Endpoints:
- `GET /api/compare/{aeTitle}/{studyUid}` - Study comparison metadata
- `GET /api/compare/{aeTitle}/{studyUid}/scans` - Scan comparisons (uses crosswalk)
- `GET /api/compare/header?original=...&anonymized=...` - Header comparison (now shows actual values)
- `GET /api/compare/ocr?path=...` - OCR detection on a DICOM file
- `GET /api/compare/image?path=...&overlay=true` - Render image with OCR overlay

### Authentication:
- Basic Auth: `admin:admin123` (base64: `YWRtaW46YWRtaW4xMjM=`)
- Example: `curl -H "Authorization: Basic YWRtaW46YWRtaW4xMjM=" http://localhost:9090/api/...`

### Crosswalk Lookup:
```java
String hashedSopUid = crosswalkStore.lookup(brokerName, originalUid, CrosswalkStore.ID_TYPE_SOP_UID);
```

### Archive Metadata Path:
```
java-app/data/{AE_TITLE}/archive/{YYYY-MM-DD}/study_{STUDY_UID}/archive_metadata.json
```

### Test Data Used:
- **Dataset:** UPENN-GBM (University of Pennsylvania Glioblastoma) from TCIA
- **Route:** Port 11112 (ANON_TEST) with `my-hashuid-dateshift-hash-naming` broker
- **Broker Config:** `hash_uids_enabled: true`

### Gotchas:
- The `brokerName` in config is the key in `honest_brokers:` section (e.g., `my-hashuid-dateshift-hash-naming`)
- Crosswalk database is stored in the data directory (SQLite)
- Archive must be enabled on the route (`enable_archive: true`) for dual-write to work
- Multiple router instances may be running from previous tests - kill them first
- Crosswalk table uses `id_in` and `id_out` columns (not `original_id` and `mapped_id`)

## 5. Instructions for New Session

Start the new session with:
```
Read java-app/handoff.md and continue from where we left off.
```

### Quick Start Commands:
```bash
# Kill any existing router instances
pkill -9 -f "dicom-router-2.1.0.jar"

# Start fresh router (with OCR support)
cd /Users/james/projects/xnat_dicom_router
TESSDATA_PREFIX=/usr/local/share/tessdata \
/Users/james/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home/bin/java \
  -Djna.library.path=/usr/local/lib \
  -Djava.library.path=/usr/local/lib \
  -Xms2g -Xmx16g -XX:+UseG1GC \
  -jar java-app/build/libs/dicom-router-2.1.0.jar \
  --config config.yaml start --admin-port 9090

# Access Review UI
open http://localhost:9090
```

### Quick Verification Commands:
```bash
# Build the project
cd /Users/james/projects/xnat_dicom_router
JAVA_HOME=/Users/james/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home java-app/gradlew -p java-app shadowJar

# Test header comparison API (should show actual values)
curl -s "http://localhost:9090/api/compare/header?original=./java-app/data/ANON_TEST/archive/2025-12-21/study_crosswalk_test/original/MR.1.3.6.1.4.1.14519.5.2.1.108466716213459599996954749662079097073.dcm&anonymized=./java-app/data/ANON_TEST/archive/2025-12-21/study_crosswalk_test/anonymized/MR.1.3.6.1.4.1.14519.5.2.1.108466716213459599996954749662079097073.dcm" -H "Authorization: Basic YWRtaW46YWRtaW4xMjM=" | python3 -m json.tool | head -40

# Test OCR on a DICOM file
curl -s "http://localhost:9090/api/compare/ocr?path=./java-app/data/ANON_TEST/archive/2025-12-21/study_crosswalk_test/original/MR.1.3.6.1.4.1.14519.5.2.1.108466716213459599996954749662079097073.dcm" -H "Authorization: Basic YWRtaW46YWRtaW4xMjM=" | python3 -m json.tool

# Check crosswalk database entries
sqlite3 java-app/data/crosswalk.db "SELECT broker_name, substr(id_in, 1, 40), substr(id_out, 1, 40), id_type FROM crosswalk WHERE broker_name='my-hashuid-dateshift-hash-naming' AND id_type='sop_uid' LIMIT 5;"
```

## 6. Session History

### 2025-12-21: Initial Implementation
- Implemented crosswalk-based UID matching in DicomComparisonService
- Added broker info storage in ArchiveMetadata
- Tested and verified crosswalk entries are created

### 2025-12-22: Header Comparison Fix
- Fixed `getTagValue()` in DicomComparisonService to show actual DICOM tag values
- Previously showed "[binary: X bytes]" for all values stored as byte arrays
- Now properly converts UIDs, names, dates, etc. to readable strings
- Tested OCR functionality (works on valid DICOM files, burned-in PHI test file is corrupt)
