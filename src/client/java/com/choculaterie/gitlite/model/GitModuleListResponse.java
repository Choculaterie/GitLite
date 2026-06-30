package com.choculaterie.gitlite.model;

import java.util.List;

/** Paginated API response wrapping a list of public module library items. */
public class GitModuleListResponse {
    public List<GitModuleListItemDto> items;
    public int page;
    public int pageSize;
    public int totalCount;
    public int totalPages;
}
