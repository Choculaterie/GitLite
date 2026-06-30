# GitMC API

GitMC is a REST API that models git concepts on top of the existing Choculaterie
schematic/account system. It is designed so that **any client** (not just this mod) can
implement push/history/fork against it.

Base URL: `https://api.choculaterie.com/api/GitMCModAPI`

## Authentication

Every endpoint requires the `X-API-Key` header, set to the same per-account API key used by
the Litematic Downloader mod API:

```
X-API-Key: <your-choculaterie-api-key>
```

Requests without a valid key receive `401 Unauthorized`. Banned/suspended accounts receive
`400 Bad Request`.

Error responses are JSON objects shaped like `{"error": "..."}`, sometimes with an additional
`"message"` field for extra detail.

## Data model

- **Repository** (`GitRepository`) — a named container owned by a user. Has a
  `defaultBranchName` (always `"main"` for now), a `visibility` (`"Public"` or private to the
  owner), and an optional `forkedFromRepositoryId` pointing at the repo it was forked from.
- **Branch** (`GitBranch`) — belongs to a repository, has a `name` and a `headCommitId`
  pointing at the latest commit on that branch.
- **Commit** (`GitCommit`) — a full snapshot, not a diff. Each commit references a
  content-addressed **blob** (`blobHash`), has a `parentCommitId` (the previous commit on the
  branch, or `null` for the first commit / a fork's root), a `message`, an `authorUsername`,
  and a `committedDate`.
- **Blob** (`GitBlob`) — a `.litematic` file stored on the FTP backend, addressed by the
  SHA256 hash of its bytes (`gitmc/blobs/{hash[0:2]}/{hash[2:4]}/{hash}.litematic`). Identical
  file contents are stored only once and shared across commits/repos/forks.

### Fork semantics

Forking a repository is **zero-copy**: it creates a new repository (with
`forkedFromRepositoryId` set), a new `main` branch, and a single new commit whose
`parentCommitId` is `null` (a fresh history root) but whose `blobHash` is identical to the
source repository's current head — no file is re-uploaded. The fork commit's message is
`"Forked from {name} @ {shortHash}"`.

## Endpoints

### `POST /repos`

Creates a new repository owned by the authenticated user, with an empty `main` branch.

Request body:
```json
{ "name": "My Contraption", "description": "Optional, max 2000 chars" }
```

Response: `201 Created`, body is a [`GitRepoDetailDto`](#gitrepodetaildto).

### `GET /repos`

Lists repositories owned by the authenticated user, newest-updated first.

Response: `200 OK`, body is a list of [`GitRepoListItemDto`](#gitrepolistitemdto).

### `GET /repos/{repoId}`

Gets a repository's details, including its branches.

Response: `200 OK`, body is a [`GitRepoDetailDto`](#gitrepodetaildto). `404 Not Found` if the
repo doesn't exist or isn't readable by the authenticated user (private repos are only
readable by their owner).

### `POST /repos/{repoId}/fork`

Forks a repository (see [Fork semantics](#fork-semantics)).

Request body:
```json
{ "newName": "Optional new name; defaults to the source repo's name" }
```

Response: `201 Created`, body is a [`GitRepoDetailDto`](#gitrepodetaildto) for the new repo.
`400 Bad Request` if the source repo has no commits yet.

### `POST /repos/{repoId}/branches/{branchName}/commits`

Pushes a new commit (a full snapshot) to a branch. Hashes the uploaded `.litematic` file with
SHA256, stores it as a content-addressed blob (deduplicated if the hash already exists),
records the commit, and moves the branch head.

Multipart form fields:
- `File` — the `.litematic` file (required, max 20 MB, `.litematic` extension only)
- `Message` — commit message (required, max 500 chars)
- `FileName` — display filename; falls back to the uploaded file's name if omitted

Response: `201 Created`, body is a [`GitCommitDetailDto`](#gitcommitdetaildto). `401
Unauthorized` if the authenticated user doesn't own the repository — only the owner can push.

### `GET /repos/{repoId}/branches/{branchName}/commits?page=&pageSize=`

Lists a branch's commit history, newest first. `page` defaults to `1`, `pageSize` defaults to
`20` (clamped to 1-50).

Response: `200 OK`, body is a [`GitCommitListResponse`](#gitcommitlistresponse).

### `GET /commits/{commitId}`

Gets a single commit's details.

Response: `200 OK`, body is a [`GitCommitDetailDto`](#gitcommitdetaildto).

### `GET /commits/{commitId}/download`

Streams the `.litematic` snapshot for a commit as `application/octet-stream` (range requests
supported).

## DTOs

### `GitRepoListItemDto`

| Field | Type | Notes |
|---|---|---|
| `id` | string | repository GUID |
| `name` | string | |
| `description` | string? | |
| `ownerUsername` | string | |
| `defaultBranchName` | string | always `"main"` currently |
| `visibility` | string | `"Public"` or owner-only |
| `forkedFromRepositoryId` | string? | set if this repo is a fork |
| `createdDate` | string | ISO 8601 |
| `updatedDate` | string | ISO 8601 |

### `GitRepoDetailDto`

All fields of `GitRepoListItemDto`, plus:

| Field | Type | Notes |
|---|---|---|
| `branches` | `GitBranchDto[]` | |

### `GitBranchDto`

| Field | Type | Notes |
|---|---|---|
| `name` | string | |
| `headCommitId` | string? | `null` if the branch has no commits yet |
| `updatedDate` | string | ISO 8601 |

### `GitCommitDetailDto`

| Field | Type | Notes |
|---|---|---|
| `id` | string | opaque commit id |
| `repositoryId` | string | |
| `parentCommitId` | string? | `null` for the first commit on a branch or a fork's root |
| `branchName` | string | |
| `message` | string | |
| `authorUsername` | string | |
| `committedDate` | string | ISO 8601 |
| `fileName` | string | |
| `fileSizeBytes` | long | |
| `blobHash` | string | SHA256 hex of the snapshot |
| `downloadUrl` | string | absolute URL to `GET /commits/{id}/download` |

### `GitCommitListResponse`

| Field | Type | Notes |
|---|---|---|
| `commits` | `GitCommitDetailDto[]` | |
| `page` | int | |
| `pageSize` | int | |
| `totalCount` | int | |
| `totalPages` | int | |

## Roadmap (not yet implemented)

- Admin-curated "modules" library: schematics flagged `isModule = true` that can be imported
  into a repo with permanent attribution to their original author, even if the module is later
  edited or removed.
- Branches beyond `main` (create/switch/merge).
- A real git wire protocol layered over the same content-addressed blob/commit storage.
