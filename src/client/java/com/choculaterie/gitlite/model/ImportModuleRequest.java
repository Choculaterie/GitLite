package com.choculaterie.gitlite.model;

/** Request body for the import-module-as-commit API endpoint. */
public record ImportModuleRequest(String moduleId, String message) {}
