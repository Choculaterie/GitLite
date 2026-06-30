package com.choculaterie.gitlite.model;

/** Request body for the create-branch API endpoint. */
public record CreateBranchRequest(String name, String fromBranch) {}
