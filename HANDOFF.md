# HANDOFF - XNAT DICOM Router UI Improvements

## 1. Context Summary

- **Project:** XNAT DICOM Router Java application with React frontend
- **Location:** `/Users/james/projects/xnat_dicom_router/java-app`
- **Original Goal:** UI improvements including separating Index/Search pages, adding batch Q/R functionality from search, and pagination
- **Why:** Enhancing usability for large datasets and enabling users to query/retrieve selected studies from search results

## 2. Current State

### Completed:
1. **Header title moved** - "XNATWorks DICOM Router" moved from sidebar to header (left of theme dropdown) with white text for visibility
2. **Batch Q/R feature** - Users can select studies in search results and send them to Query/Retrieve page
3. **Search limit increased** - Changed from 100 to 1000 results max
4. **Pagination implemented** - 50 studies per page with navigation controls

### Files Modified:
- `ui/src/App.tsx` - Moved title to header-right, simplified sidebar header to just icon
- `ui/src/pages/Search.tsx` - Added selection state, pagination state, handlers, and UI controls
- `ui/src/pages/QueryRetrieve.tsx` - Added sessionStorage reading for bulk mode

### Key Implementation Details:

**Search.tsx changes:**
```tsx
// Selection state for batch Q/R
const [selectedStudies, setSelectedStudies] = useState<Set<string>>(new Set())

// Pagination state
const [currentPage, setCurrentPage] = useState(1)
const pageSize = 50

// Pagination calculations
const totalPages = searchResults ? Math.ceil(searchResults.studies.length / pageSize) : 0
const startIndex = (currentPage - 1) * pageSize
const endIndex = startIndex + pageSize
const paginatedStudies = searchResults?.studies.slice(startIndex, endIndex) || []

// Q/R handler stores UIDs in sessionStorage and navigates
const handleQueryRetrieveSelected = () => {
  sessionStorage.setItem('qr_bulk_uids', Array.from(selectedStudies).join('\n'))
  sessionStorage.setItem('qr_bulk_type', 'studyInstanceUID')
  navigate('/query-retrieve?mode=bulk')
}
```

**QueryRetrieve.tsx changes:**
```tsx
// Read from sessionStorage on mount
useEffect(() => {
  const mode = searchParams.get('mode')
  if (mode === 'bulk') {
    const storedUIDs = sessionStorage.getItem('qr_bulk_uids')
    const storedType = sessionStorage.getItem('qr_bulk_type')
    if (storedUIDs) {
      setQueryMode('bulk')
      setBulkIdentifiers(storedUIDs)
      if (storedType === 'studyInstanceUID' || ...) {
        setBulkIdentifierType(storedType)
      }
      sessionStorage.removeItem('qr_bulk_uids')
      sessionStorage.removeItem('qr_bulk_type')
    }
  }
}, [searchParams])
```

## 3. Next Steps

1. **Rebuild and test the UI**
   ```bash
   cd /Users/james/projects/xnat_dicom_router/java-app/ui
   npm run build
   ```

2. **Restart the Java server** (if not using Gradle continuous build)
   ```bash
   pkill -f 'dicom-router-2.0.0.jar' 2>/dev/null
   cd /Users/james/projects/xnat_dicom_router/java-app
   ./gradlew bootRun
   ```
   Or use the existing background process pattern from the session.

3. **Test the features:**
   - Search page loads with pagination showing 50 items per page
   - Page navigation works (First, Prev, Next, Last)
   - Study checkboxes work, Select All selects all studies (not just visible page)
   - "Q/R Selected" button navigates to Query/Retrieve with UIDs pre-filled
   - Header shows "XNATWorks DICOM Router" title in white text

4. **Potential improvements** (not requested but could be useful):
   - Page size selector (25/50/100)
   - Jump to specific page input
   - Persist pagination across searches

## 4. Key Information

### File Paths:
- UI source: `ui/src/`
- Main app: `ui/src/App.tsx`
- Search page: `ui/src/pages/Search.tsx`
- Query/Retrieve page: `ui/src/pages/QueryRetrieve.tsx`
- CSS: `ui/src/index.css`
- Build output: `src/main/resources/static/`

### API Endpoints:
- Search: `GET /api/search/studies`
- Aggregations: `GET /api/search/stats/aggregations`
- Study details: `GET /api/search/studies/{studyUid}`

### Background Processes:
There are multiple stale background Java processes from previous runs. Clean them up:
```bash
pkill -f 'dicom-router-2.0.0.jar'
```

### CSS Variables Used:
- `--header-text` - Header text color (white in dark themes)
- `--border-color` - Used for pagination separator
- `--text-light` - Muted text color
- `--bg-alt` - Alternative background color

## 5. Instructions for New Session

Start the new session with:
```
Read HANDOFF.md and continue from where we left off.
```

The pagination implementation is complete in the code. The next step is to:
1. Rebuild the UI: `cd ui && npm run build`
2. Restart the server and test the functionality
