package com.choculaterie.gitlite.model;

/** API response DTO representing a single commit, including its attached file metadata. */
public class GitCommitDto {
	public String id;
	public String repositoryId;
	public String parentCommitId;
	public String branchName;
	public String message;
	public String authorUsername;
	public String committedDate;
	public String fileName;
	public String blobHash;
	public String downloadUrl;
	public long fileSizeBytes;
}
