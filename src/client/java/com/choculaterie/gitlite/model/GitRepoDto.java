package com.choculaterie.gitlite.model;

/** API response DTO representing a repository summary as returned by the list-repos endpoint. */
public class GitRepoDto {
	public String id;
	public String name;
	public String description;
	public String ownerUsername;
	public String defaultBranchName;
	public String visibility;
	public String createdDate;
	public String updatedDate;
}
