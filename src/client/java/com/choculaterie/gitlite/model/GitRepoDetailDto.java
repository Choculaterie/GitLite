package com.choculaterie.gitlite.model;

import java.util.List;

/** Extended repository DTO that includes the full branch list; returned by the single-repo endpoint. */
public class GitRepoDetailDto extends GitRepoDto {
	public List<GitBranchDto> branches;
	public GitZoneDto zone;
}
