/*******************************************************************************
 * Copyright (c) 2015 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License, v.
 * 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.analysis.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import nu.yona.server.analysis.entities.Activity;
import nu.yona.server.analysis.entities.DayActivity;
import nu.yona.server.analysis.entities.GoalConflictMessage;
import nu.yona.server.analysis.entities.WeekActivity;
import nu.yona.server.goals.entities.Goal;
import nu.yona.server.goals.service.ActivityCategoryDTO;
import nu.yona.server.goals.service.ActivityCategoryService;
import nu.yona.server.messaging.service.MessageService;
import nu.yona.server.properties.YonaProperties;
import nu.yona.server.subscriptions.entities.UserAnonymized;
import nu.yona.server.subscriptions.service.UserAnonymizedDTO;
import nu.yona.server.subscriptions.service.UserAnonymizedService;
import nu.yona.server.subscriptions.service.UserService;

@Service
public class AnalysisEngineService
{
	@Autowired
	private YonaProperties yonaProperties;
	@Autowired
	private ActivityCategoryService activityCategoryService;
	@Autowired
	private AnalysisEngineCacheService cacheService;
	@Autowired
	private UserService userService;
	@Autowired
	private UserAnonymizedService userAnonymizedService;
	@Autowired
	private MessageService messageService;

	public void analyze(UUID userID, AppActivityDTO[] appActivities)
	{
		UUID userAnonymizedID = userService.getPrivateUser(userID).getPrivateData().getUserAnonymizedID();
		UserAnonymizedDTO userAnonymized = userAnonymizedService.getUserAnonymized(userAnonymizedID);
		for (AppActivityDTO appActivity : appActivities)
		{
			Set<ActivityCategoryDTO> matchingActivityCategories = activityCategoryService
					.getMatchingCategoriesForApp(appActivity.getApplication());
			analyze(new ActivityPayload(appActivity), userAnonymized, matchingActivityCategories);
		}
	}

	public void analyze(NetworkActivityDTO networkActivity)
	{
		UserAnonymizedDTO userAnonymized = userAnonymizedService.getUserAnonymized(networkActivity.getVPNLoginID());
		Set<ActivityCategoryDTO> matchingActivityCategories = activityCategoryService
				.getMatchingCategoriesForSmoothwallCategories(networkActivity.getCategories());
		analyze(new ActivityPayload(networkActivity), userAnonymized, matchingActivityCategories);
	}

	private void analyze(ActivityPayload payload, UserAnonymizedDTO userAnonymized,
			Set<ActivityCategoryDTO> matchingActivityCategories)
	{
		Set<Goal> matchingGoalsOfUser = determineMatchingGoalsForUser(userAnonymized, matchingActivityCategories);
		for (Goal matchingGoalOfUser : matchingGoalsOfUser)
		{
			addOrUpdateActivity(payload, userAnonymized, matchingGoalOfUser);
		}
	}

	private void addOrUpdateActivity(ActivityPayload payload, UserAnonymizedDTO userAnonymized, Goal matchingGoal)
	{
		Date minEndTime = new Date(payload.startTime.getTime() - yonaProperties.getAnalysisService().getConflictInterval());
		DayActivity dayActivity = cacheService.fetchDayActivityForUser(userAnonymized.getID(), matchingGoal.getID());
		if (dayActivity != null && dayActivity.getStartTime().isBefore(getStartOfDay(payload.startTime, userAnonymized)))
		{
			// Last day cached was not today
			dayActivity = null;
		}
		Activity activity = dayActivity == null ? null : dayActivity.getLastActivity();

		if (activity == null || activity.getEndTime().before(minEndTime))
		{
			addActivity(payload, userAnonymized, matchingGoal, dayActivity);
		}
		// Update message only if it is within five seconds to avoid unnecessary cache flushes.
		// Or if the start time is earlier than the existing start time (this can happen with app activity, see below).
		else if (payload.endTime.getTime() - activity.getEndTime().getTime() >= yonaProperties.getAnalysisService()
				.getUpdateSkipWindow() || payload.startTime.before(activity.getStartTime()))
		{
			updateActivity(payload, userAnonymized, matchingGoal, dayActivity, activity);
		}
	}

	private void addActivity(ActivityPayload payload, UserAnonymizedDTO userAnonymized, Goal matchingGoal,
			DayActivity dayActivity)
	{
		dayActivity = createNewActivity(dayActivity, payload, userAnonymized, matchingGoal);
		cacheService.updateDayActivityForUser(dayActivity);

		if (matchingGoal.isNoGoGoal())
		{
			sendConflictMessageToAllDestinationsOfUser(payload, userAnonymized, dayActivity.getLastActivity(), matchingGoal);
		}
	}

	private void updateActivity(ActivityPayload payload, UserAnonymizedDTO userAnonymized, Goal matchingGoal,
			DayActivity dayActivity, Activity activity)
	{
		updateLastActivity(dayActivity, payload, userAnonymized, matchingGoal, activity);
		cacheService.updateDayActivityForUser(dayActivity);
	}

	private ZonedDateTime getStartOfDay(Date startTime, UserAnonymizedDTO userAnonymized)
	{
		return startTime.toInstant().atZone(ZoneId.of(userAnonymized.getTimeZoneId())).truncatedTo(ChronoUnit.DAYS);
	}

	private ZonedDateTime getStartOfWeek(Date startTime, UserAnonymizedDTO userAnonymized)
	{
		ZonedDateTime startOfDay = getStartOfDay(startTime, userAnonymized);
		switch (startOfDay.getDayOfWeek())
		{
			case SUNDAY:
				// take as the first day of week
				return startOfDay;
			default:
				// MONDAY=1, etc.
				return startOfDay.minusDays(startOfDay.getDayOfWeek().getValue());
		}
	}

	private DayActivity createNewActivity(DayActivity dayActivity, ActivityPayload payload, UserAnonymizedDTO userAnonymized,
			Goal matchingGoal)
	{
		if (dayActivity == null)
		{
			dayActivity = createNewDayActivity(payload, userAnonymized, matchingGoal);
		}

		Activity activity = Activity.createInstance(payload.startTime, payload.endTime);
		dayActivity.addActivity(activity);
		return dayActivity;
	}

	private DayActivity createNewDayActivity(ActivityPayload payload, UserAnonymizedDTO userAnonymized, Goal matchingGoal)
	{
		UserAnonymized userAnonymizedEntity = userAnonymizedService.getUserAnonymizedEntity(userAnonymized.getID());

		DayActivity dayActivity = DayActivity.createInstance(userAnonymizedEntity, matchingGoal,
				getStartOfDay(payload.startTime, userAnonymized));

		ZonedDateTime startOfWeek = getStartOfWeek(payload.startTime, userAnonymized);
		WeekActivity weekActivity = cacheService.fetchWeekActivityForUser(userAnonymized.getID(), matchingGoal.getID(),
				startOfWeek.toLocalDate());
		if (weekActivity == null)
		{
			weekActivity = WeekActivity.createInstance(userAnonymizedEntity, matchingGoal, startOfWeek);
		}
		weekActivity.addDayActivity(dayActivity);
		cacheService.updateWeekActivityForUser(weekActivity);

		return dayActivity;
	}

	private void updateLastActivity(DayActivity dayActivity, ActivityPayload payload, UserAnonymizedDTO userAnonymized,
			Goal matchingGoal, Activity activity)
	{
		assert userAnonymized.getID().equals(dayActivity.getUserAnonymized().getID());
		assert matchingGoal.getID().equals(dayActivity.getGoal().getID());

		activity.setEndTime(payload.endTime);
		if (payload.startTime.before(activity.getStartTime()))
		{
			// This can happen if app activity is posted after some
			// network activity is posted during the use of the same app.
			// Solution: extend start time of the existing activity.
			// Notice this will possible create overlap with other network activity posted earlier, but after the start of the
			// app.
			activity.setStartTime(payload.startTime);
		}
	}

	public Set<String> getRelevantSmoothwallCategories()
	{
		return activityCategoryService.getAllActivityCategories().stream().flatMap(g -> g.getSmoothwallCategories().stream())
				.collect(Collectors.toSet());
	}

	private void sendConflictMessageToAllDestinationsOfUser(ActivityPayload payload, UserAnonymizedDTO userAnonymized,
			Activity activity, Goal matchingGoal)
	{
		GoalConflictMessage selfGoalConflictMessage = GoalConflictMessage.createInstance(userAnonymized.getID(), activity,
				matchingGoal, payload.url);
		messageService.sendMessage(selfGoalConflictMessage, userAnonymized.getAnonymousDestination());

		messageService.broadcastMessageToBuddies(userAnonymized,
				() -> GoalConflictMessage.createInstanceFromBuddy(userAnonymized.getID(), selfGoalConflictMessage));
	}

	private Set<Goal> determineMatchingGoalsForUser(UserAnonymizedDTO userAnonymized,
			Set<ActivityCategoryDTO> matchingActivityCategories)
	{
		Set<UUID> matchingActivityCategoryIDs = matchingActivityCategories.stream().map(ac -> ac.getID())
				.collect(Collectors.toSet());
		Set<Goal> goalsOfUser = userAnonymized.getGoals();
		Set<Goal> matchingGoalsOfUser = goalsOfUser.stream()
				.filter(g -> matchingActivityCategoryIDs.contains(g.getActivityCategory().getID())).collect(Collectors.toSet());
		return matchingGoalsOfUser;
	}

	public Page<WeekActivityDTO> getWeekActivity(UUID userID, Pageable pageable)
	{
		UUID userAnonymizedID = userService.getPrivateUser(userID).getPrivateData().getUserAnonymizedID();
		Page<WeekActivity> weekActivityEntitiesPage = WeekActivity.getRepository().findAll(userAnonymizedID, pageable);
		return new PageImpl<WeekActivityDTO>(weekActivityEntitiesPage.getContent().stream()
				.map(a -> WeekActivityDTO.createInstance(a)).collect(Collectors.toList()), pageable,
				weekActivityEntitiesPage.getTotalElements());
	}

	public Page<DayActivityDTO> getDayActivity(UUID userID, Pageable pageable)
	{
		UUID userAnonymizedID = userService.getPrivateUser(userID).getPrivateData().getUserAnonymizedID();
		Page<DayActivity> dayActivityEntitiesPage = DayActivity.getRepository().findAll(userAnonymizedID, pageable);
		return new PageImpl<DayActivityDTO>(dayActivityEntitiesPage.getContent().stream()
				.map(a -> DayActivityDTO.createInstance(a)).collect(Collectors.toList()), pageable,
				dayActivityEntitiesPage.getTotalElements());
	}

	public WeekActivityDTO getWeekActivity(UUID userID, LocalDate date, UUID goalID)
	{
		UUID userAnonymizedID = userService.getPrivateUser(userID).getPrivateData().getUserAnonymizedID();
		return WeekActivityDTO.createInstance(WeekActivity.getRepository().findOne(userAnonymizedID, date, goalID));
	}

	public DayActivityDTO getDayActivity(UUID userID, LocalDate date, UUID goalID)
	{
		UUID userAnonymizedID = userService.getPrivateUser(userID).getPrivateData().getUserAnonymizedID();
		return DayActivityDTO.createInstance(DayActivity.getRepository().findOne(userAnonymizedID, date, goalID));
	}

	class ActivityPayload
	{
		public final String url;
		public final Date startTime;
		public final Date endTime;
		public final String application;

		public ActivityPayload(NetworkActivityDTO networkActivity)
		{
			this.url = networkActivity.getURL();
			this.startTime = new Date(); // now
			this.endTime = this.startTime;
			this.application = null;
		}

		public ActivityPayload(AppActivityDTO appActivity)
		{
			this.url = null;
			this.startTime = appActivity.getStartTime();
			this.endTime = appActivity.getEndTime();
			this.application = appActivity.getApplication();
		}
	}
}
