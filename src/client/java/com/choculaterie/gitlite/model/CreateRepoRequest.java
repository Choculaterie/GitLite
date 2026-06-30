package com.choculaterie.gitlite.model;

/** Request body for the create-repository API endpoint. */
public class CreateRepoRequest {
	public String name;
	public String description;

	public CreateRepoRequest(String name, String description) {
		this.name = name;
		this.description = description;
	}
}
