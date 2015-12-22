/*******************************************************************************
 * Copyright (c) 2015 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License, v.
 * 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.analysis.service;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import nu.yona.server.analysis.entities.GoalConflictMessage;
import nu.yona.server.goals.entities.Goal;
import nu.yona.server.goals.service.GoalService;
import nu.yona.server.messaging.service.MessageDestinationDTO;
import nu.yona.server.messaging.service.MessageService;
import nu.yona.server.properties.YonaProperties;
import nu.yona.server.subscriptions.service.UserAnonymizedDTO;
import nu.yona.server.subscriptions.service.UserService;

@Service
public class AnalysisEngineService
{
	@Autowired
	private YonaProperties yonaProperties;
	@Autowired
	private GoalService goalService;
	@Autowired
	private AnalysisEngineCacheService cacheService;
	@Autowired
	private UserService userService;
	@Autowired
	private MessageService messageService;

	public void analyze(PotentialConflictDTO potentialConflictPayload)
	{
		UserAnonymizedDTO userAnonimized = userService.getUserAnonymized(potentialConflictPayload.getVPNLoginID());
		Set<Goal> conflictingGoalsOfUser = determineConflictingGoalsForUser(userAnonimized,
				potentialConflictPayload.getCategories());
		if (!conflictingGoalsOfUser.isEmpty())
		{
			sendConflictMessageToAllDestinationsOfUser(potentialConflictPayload, userAnonimized, conflictingGoalsOfUser);
		}
	}

	public Set<String> getRelevantCategories()
	{
		return goalService.getAllGoals().stream().flatMap(g -> g.getCategories().stream()).collect(Collectors.toSet());
	}

	private void sendConflictMessageToAllDestinationsOfUser(PotentialConflictDTO payload, UserAnonymizedDTO userAnonymized,
			Set<Goal> conflictingGoalsOfUser)
	{
		GoalConflictMessage selfGoalConflictMessage = sendOrUpdateConflictMessage(payload, conflictingGoalsOfUser,
				userAnonymized.getAnonymousDestination(), null);

		userAnonymized.getBuddyDestinations().stream()
				.forEach(d -> sendOrUpdateConflictMessage(payload, conflictingGoalsOfUser, d, selfGoalConflictMessage));
	}

	private GoalConflictMessage sendOrUpdateConflictMessage(PotentialConflictDTO payload, Set<Goal> conflictingGoalsOfUser,
			MessageDestinationDTO destination, GoalConflictMessage origin)
	{
		Date now = new Date();
		Date minEndTime = new Date(now.getTime() - yonaProperties.getAnalysisService().getConflictInterval());
		Goal conflictingGoal = conflictingGoalsOfUser.iterator().next();
		GoalConflictMessage message = cacheService.fetchLatestGoalConflictMessageForUser(payload.getVPNLoginID(),
				conflictingGoal.getID(), destination, minEndTime);

		if (message == null || message.getEndTime().before(minEndTime))
		{
			message = sendNewGoalConflictMessage(payload, conflictingGoal, destination, origin);
			cacheService.updateLatestGoalConflictMessageForUser(message, destination);
		}
		// Update message only if it is within five seconds to avoid unnecessary cache flushes.
		else if (now.getTime() - message.getEndTime().getTime() >= yonaProperties.getAnalysisService().getUpdateSkipWindow())
		{
			updateLastGoalConflictMessage(payload, now, conflictingGoal, message);
			cacheService.updateLatestGoalConflictMessageForUser(message, destination);
		}

		return message;
	}

	private GoalConflictMessage sendNewGoalConflictMessage(PotentialConflictDTO payload, Goal conflictingGoal,
			MessageDestinationDTO destination, GoalConflictMessage origin)
	{
		GoalConflictMessage message;
		if (origin == null)
		{
			message = GoalConflictMessage.createInstance(payload.getVPNLoginID(), conflictingGoal, payload.getURL());
		}
		else
		{
			message = GoalConflictMessage.createInstanceFromBuddy(payload.getVPNLoginID(), origin);
		}
		messageService.sendMessage(message, destination);
		return message;
	}

	private void updateLastGoalConflictMessage(PotentialConflictDTO payload, Date messageEndTime, Goal conflictingGoal,
			GoalConflictMessage message)
	{
		assert payload.getVPNLoginID().equals(message.getRelatedUserAnonymizedID());
		assert conflictingGoal.getID().equals(message.getGoal().getID());

		message.setEndTime(messageEndTime);
	}

	private Set<Goal> determineConflictingGoalsForUser(UserAnonymizedDTO userAnonymized, Set<String> categories)
	{
		Set<Goal> allGoals = goalService.getAllGoalEntities();
		Set<Goal> conflictingGoals = allGoals.stream().filter(g -> {
			Set<String> goalCategories = new HashSet<>(g.getCategories());
			goalCategories.retainAll(categories);
			return !goalCategories.isEmpty();
		}).collect(Collectors.toSet());
		Set<String> goalsOfUser = userAnonymized.getGoals();
		Set<Goal> conflictingGoalsOfUser = conflictingGoals.stream().filter(g -> goalsOfUser.contains(g.getName()))
				.collect(Collectors.toSet());
		return conflictingGoalsOfUser;
	}
}
