package com.choculaterie.gitlite.model;

import java.util.List;

/** Paginated list of {@link GitReleaseDto}. */
public class GitReleaseListResponse {
	public List<GitReleaseDto> items;
	public int page;
	public int pageSize;
	public int totalCount;
	public int totalPages;
}
