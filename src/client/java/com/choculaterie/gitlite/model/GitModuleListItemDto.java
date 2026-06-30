package com.choculaterie.gitlite.model;

/** API response DTO representing a single item in the public module library listing. */
public class GitModuleListItemDto {
    public String id;
    public String name;
    public String authorName;
    public String description;
    public String publishDate;
    public int downloadCount;
    public boolean hasThumbnail;
}
