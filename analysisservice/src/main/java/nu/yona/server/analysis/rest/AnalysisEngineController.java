/*******************************************************************************
 * Copyright (c) 2015, 2016 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.analysis.rest;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import nu.yona.server.analysis.service.AnalysisEngineService;
import nu.yona.server.analysis.service.AppActivityDTO;
import nu.yona.server.analysis.service.NetworkActivityDTO;

@Controller
@RequestMapping(value = "", produces = { MediaType.APPLICATION_JSON_VALUE })
public class AnalysisEngineController
{
	@Autowired
	private AnalysisEngineService analysisEngineService;

	@RequestMapping(value = "/userAnonymized/{userAnonymizedID}/networkActivity/", method = RequestMethod.POST)
	@ResponseStatus(value = HttpStatus.OK)
	public void analyzeNetworkActivity(@PathVariable UUID userAnonymizedID,
			@RequestBody NetworkActivityDTO potentialConflictPayload)
	{
		analysisEngineService.analyze(userAnonymizedID, potentialConflictPayload);
	}

	/**
	 * The app service receives the app activity monitored by the Yona app and sends that to the analysis engine through this
	 * method.
	 */
	@RequestMapping(value = "/userAnonymized/{userAnonymizedID}/appActivity/", method = RequestMethod.POST)
	@ResponseStatus(value = HttpStatus.OK)
	public void analyzeAppActivity(@PathVariable UUID userAnonymizedID, @RequestBody AppActivityDTO appActivities)
	{
		analysisEngineService.analyze(userAnonymizedID, appActivities);
	}

	@RequestMapping(value = "/relevantSmoothwallCategories/", method = RequestMethod.GET)
	@ResponseBody
	public HttpEntity<CategoriesResource> getRelevantSmoothwallCategories()
	{
		CategoriesDTO categories = new CategoriesDTO(analysisEngineService.getRelevantSmoothwallCategories());
		return new ResponseEntity<CategoriesResource>(new CategoriesResource(categories), HttpStatus.OK);
	}

	public static class CategoriesResource extends Resource<CategoriesDTO>
	{
		public CategoriesResource(CategoriesDTO categories)
		{
			super(categories);
		}
	}
}
