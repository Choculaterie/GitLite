package com.choculaterie.gitlite.model;

import java.util.List;

/** Paginated API response wrapping a list of commits for a branch. */
public class GitCommitListResponse {
	public List<GitCommitDto> commits;
	public int page;
	public int pageSize;
	public int totalCount;
	public int totalPages;
}
