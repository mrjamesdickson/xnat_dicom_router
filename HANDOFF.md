# HANDOFF: Enhanced Retry Workflow with Archive and Review

## 1. Context Summary
- **Project**: XNAT DICOM Router (`/Users/james/projects/xnat_dicom_router/java-app`)
- **Goal**: Implement comprehensive enhancement to the DICOM router for better retry handling, audit trails, and review workflows
- **Why**: When sessions fail and need retry, routes with anonymization need access to original files (not anonymized). Also need audit trail and human-in-the-loop review for anonymized studies before sending.

## 2. Current State

### Completed
- Plan created and approved at `/Users/james/.claude/plans/delegated-floating-bear.md`
- GitHub repo made private
- Router currently running in background on port 9090

### Key Decisions Made
1. **Option C: Full Enhancement** selected - includes archive, per-destination tracking, and review workflow
2. **Per-route config** for review requirement (not global)
3. **Per-destination retry** - only retry failed destinations, not entire study

### New Folder Structure (to implement)
```
{baseDir}/{aeTitle}/
├── incoming/                       # Unchanged
├── processing/                     # Unchanged
├── archive/                        # NEW
│   └── {date}/study_{uid}/
│       ├── original/
│       ├── anonymized/
│       ├── audit_report.json
│       └── destinations/{dest}.json
├── pending_review/                 # NEW
├── completed/
├── partial/                        # NEW (some destinations succeeded)
├── failed/
├── rejected/                       # NEW
├── deleted/
├── logs/
└── history/
```

## 3. Next Steps

### Phase 1: Archive Infrastructure (START HERE)
1. Create `src/main/java/io/xnatworks/router/archive/ArchiveManager.java`
   - `archiveOriginal(study)` - Copy original files to archive
   - `archiveAnonymized(study)` - Save anonymized files to archive
   - `generateAuditReport(study)` - Create diff between original/anonymized
   - `getArchivedStudy(studyUid)` - Retrieve archived data

2. Add `archive_retention_days` config to `AppConfig.java`

3. Modify `DicomRouter.java` processing pipeline:
   - After study received: Copy to `archive/{date}/study_{uid}/original/`
   - After anonymization: Save to `archive/{date}/study_{uid}/anonymized/`
   - Generate audit report

4. Modify `StorageCleanupService.java` for archive cleanup

### Phase 2: Per-Destination Tracking
5. Enhance `TransferTracker.java` with `DestinationStatus` class
6. Create `RetryManager.java`
7. Add API endpoints to `TransfersResource.java`

### Phase 3: Review Workflow
8. Create `ReviewManager.java`
9. Create `ReviewResource.java` with REST endpoints
10. Modify processing pipeline for `require_review` routes

### Phase 4: UI Updates
11. Create `ui/src/pages/ReviewQueue.tsx`
12. Create `ui/src/pages/ArchiveBrowser.tsx`
13. Update `ui/src/App.tsx` and `ui/src/components/Sidebar.tsx`

## 4. Key Information

### File Paths
- **Plan document**: `/Users/james/.claude/plans/delegated-floating-bear.md`
- **Main router**: `src/main/java/io/xnatworks/router/DicomRouter.java`
- **Transfer tracking**: `src/main/java/io/xnatworks/router/tracking/TransferTracker.java`
- **Config**: `src/main/java/io/xnatworks/router/config/AppConfig.java`
- **Storage cleanup**: `src/main/java/io/xnatworks/router/tracking/StorageCleanupService.java`
- **API hooks**: `ui/src/hooks/useApi.ts`

### Config Changes (to add to config.yaml)
```yaml
resilience:
  archive_retention_days: 365      # NEW

routes:
  - ae_title: CT_RESEARCH
    require_review: true           # NEW per-route config
    destinations:
      - destination: xnat-prod
        max_retries: 3
        retry_delay_seconds: 300
```

### Gotchas
- Router running in background (multiple background shells from previous session)
- Current anonymization already works on temp copies (originals preserved in failed folder)
- Need to kill old router processes before rebuilding: `pkill -f "dicom-router-2.0.0.jar"`

### Build Commands
```bash
cd /Users/james/projects/xnat_dicom_router/java-app
export JAVA_HOME=/Users/james/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home
./gradlew shadowJar
```

## 5. Instructions for New Session

Start the new session with:
```
Read HANDOFF.md and continue from where we left off.
```

Then begin implementing Phase 1 by creating `ArchiveManager.java`. Read the full plan at `/Users/james/.claude/plans/delegated-floating-bear.md` for complete details on all phases.
