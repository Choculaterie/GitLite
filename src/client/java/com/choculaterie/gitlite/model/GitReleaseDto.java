package com.choculaterie.gitlite.model;

/** API response DTO representing a repository release: a commit published as a real, public schematic. */
public class GitReleaseDto {
	public int id;
	public String tagName;
	public String schematicId;
	public String schematicName;
	public String thumbnailUrl;
	public int downloadCount;
	public String commitId;
	public String commitMessage;
	public String createdDate;
}
