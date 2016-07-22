/*******************************************************************************
 * Copyright (c) 2015 Stichting Yona Foundation
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v.2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server

import groovy.json.*

import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.temporal.IsoFields

import nu.yona.server.test.AppActivity
import nu.yona.server.test.Buddy
import nu.yona.server.test.Goal
import nu.yona.server.test.User

class ActivityTest extends AbstractAppServiceIntegrationTest
{
	def 'Fetch activity reports without activity'()
	{
		given:
		def richard = addRichard()
		Goal goal = richard.findActiveGoal(GAMBLING_ACT_CAT_URL)

		when:
		def responseDayOverviews = appService.getDayActivityOverviews(richard)
		def responseWeekOverviews = appService.getWeekActivityOverviews(richard)

		then:
		assertDayOverviewBasics(responseDayOverviews, 1, 1)
		assertWeekOverviewBasics(responseWeekOverviews, [2], 1)

		def weekActivityForGoal = responseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[0].weekActivities.find{ it._links."yona:goal".href == goal.url}
		assert weekActivityForGoal?._links?."yona:weekDetails"?.href
		def weekActivityDetailUrl = weekActivityForGoal?._links?."yona:weekDetails"?.href
		def response = appService.getResourceWithPassword(weekActivityDetailUrl, richard.password)
		assert response.status == 200

		def dayActivityOverview = responseDayOverviews.responseData._embedded."yona:dayActivityOverviews"[0]
		def dayActivityForGoal = dayActivityOverview.dayActivities.find{ it._links."yona:goal".href == goal.url}
		assert dayActivityForGoal?._links?."yona:dayDetails"?.href
		def dayActivityDetailUrl =  dayActivityForGoal?._links?."yona:dayDetails"?.href
		def responseDayDetail = appService.getResourceWithPassword(dayActivityDetailUrl, richard.password)
		assert responseDayDetail.status == 200

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Page through multiple weeks'()
	{
		given:
		def richard = addRichard()

		setGoalCreationTime(richard, NEWS_ACT_CAT_URL, "W-4 Mon 02:18")

		richard = appService.reloadUser(richard)
		Goal budgetGoalNews = richard.findActiveGoal(NEWS_ACT_CAT_URL)

		def currentDayOfWeek = YonaServer.getCurrentDayOfWeek()
		def expectedTotalDays = 6 + 7*3 + currentDayOfWeek + 1
		def expectedTotalWeeks = 5

		when:
		//we can safely get two normal pages
		def responseWeekOverviewsPage1 = appService.getWeekActivityOverviews(richard)
		def responseWeekOverviewsPage2 = appService.getWeekActivityOverviews(richard, ["page": 1])
		def responseWeekOverviewsPage3 = appService.getWeekActivityOverviews(richard, ["page": 2])
		//we can safely get two normal pages
		def responseDayOverviewsPage1 = appService.getDayActivityOverviews(richard)
		def responseDayOverviewsPage2 = appService.getDayActivityOverviews(richard, ["page": 1])
		def responseDayOverviewsAllOnOnePage = appService.getDayActivityOverviews(richard, ["size": expectedTotalDays])

		then:
		assertWeekOverviewBasics(responseWeekOverviewsPage1, [2, 1], expectedTotalWeeks)
		assertWeekOverviewBasics(responseWeekOverviewsPage2, [1, 1], expectedTotalWeeks)
		assertWeekOverviewBasics(responseWeekOverviewsPage3, [1], expectedTotalWeeks)

		def week5ForGoal = responseWeekOverviewsPage3.responseData._embedded."yona:weekActivityOverviews"[0].weekActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		def week4ForGoal = responseWeekOverviewsPage2.responseData._embedded."yona:weekActivityOverviews"[1].weekActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		def week3ForGoal = responseWeekOverviewsPage2.responseData._embedded."yona:weekActivityOverviews"[0].weekActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		def week2ForGoal = responseWeekOverviewsPage1.responseData._embedded."yona:weekActivityOverviews"[1].weekActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		def week1ForGoal = responseWeekOverviewsPage1.responseData._embedded."yona:weekActivityOverviews"[0].weekActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		assertWeekDetailPrevNextLinks(richard, week5ForGoal, null, week4ForGoal)
		assertWeekDetailPrevNextLinks(richard, week4ForGoal, week5ForGoal, week3ForGoal)
		assertWeekDetailPrevNextLinks(richard, week1ForGoal, week2ForGoal, null)

		assertDayOverviewBasics(responseDayOverviewsPage1, 3, expectedTotalDays)
		assertDayOverviewBasics(responseDayOverviewsPage2, 3, expectedTotalDays)

		def day1ForGoal = responseDayOverviewsPage1.responseData._embedded."yona:dayActivityOverviews"[0].dayActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		def day2ForGoal = responseDayOverviewsPage1.responseData._embedded."yona:dayActivityOverviews"[1].dayActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		def day3ForGoal = responseDayOverviewsPage1.responseData._embedded."yona:dayActivityOverviews"[2].dayActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		def secondToLastDayForGoal = responseDayOverviewsAllOnOnePage.responseData._embedded."yona:dayActivityOverviews"[expectedTotalDays - 2].dayActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		def lastDayForGoal = responseDayOverviewsAllOnOnePage.responseData._embedded."yona:dayActivityOverviews"[expectedTotalDays - 1].dayActivities.find{ it._links."yona:goal".href == budgetGoalNews.url}
		assertDayDetailPrevNextLinks(richard, lastDayForGoal, null, secondToLastDayForGoal)
		assertDayDetailPrevNextLinks(richard, day2ForGoal, day3ForGoal, day1ForGoal)
		assertDayDetailPrevNextLinks(richard, day1ForGoal, day2ForGoal, null)

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Retrieve activity report of previous week'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		User richard = richardAndBob.richard
		User bob = richardAndBob.bob

		setGoalCreationTime(richard, NEWS_ACT_CAT_URL, "W-1 Mon 02:18")
		reportAppActivity(richard, "NU.nl", "W-1 Mon 03:15", "W-1 Mon 03:35")
		reportAppActivities(richard, [createAppActivity("NU.nl", "W-1 Tue 08:45", "W-1 Tue 09:10"), createAppActivity("Facebook", "W-1 Tue 09:35", "W-1 Tue 10:10")])
		addTimeZoneGoal(richard, SOCIAL_ACT_CAT_URL, ["11:00-12:00"], "W-1 Wed 13:55")
		reportNetworkActivity(richard, ["social"], "http://www.facebook.com", "W-1 Wed 15:00")
		reportNetworkActivity(richard, ["social"], "http://www.facebook.com", "W-1 Thu 11:30")

		addBudgetGoal(bob, SOCIAL_ACT_CAT_URL, 180, "W-1 Thu 18:00")
		reportAppActivity(bob, "Facebook", "W-1 Thu 20:00", "W-1 Thu 20:35")

		addTimeZoneGoal(bob, MULTIMEDIA_ACT_CAT_URL, ["20:00-22:00"], "W-1 Fri 14:00")
		reportNetworkActivity(bob, ["YouTube"], "http://www.youtube.com", "W-1 Fri 15:00")
		reportNetworkActivity(bob, ["YouTube"], "http://www.youtube.com", "W-1 Sat 21:00")

		richard = appService.reloadUser(richard)
		Goal budgetGoalNewsRichard = richard.findActiveGoal(NEWS_ACT_CAT_URL)
		Goal timeZoneGoalSocialRichard = richard.findActiveGoal(SOCIAL_ACT_CAT_URL)

		bob = appService.reloadUser(bob)
		Goal budgetGoalSocialBob = bob.findActiveGoal(SOCIAL_ACT_CAT_URL)
		Goal timeZoneGoalMultimediaBob = bob.findActiveGoal(MULTIMEDIA_ACT_CAT_URL)

		def expectedValuesRichardLastWeek = [
			"Mon" : [[goal:budgetGoalNewsRichard, data: [goalAccomplished: false, minutesBeyondGoal: 20, spread: [13 : 15, 14 : 5]]]],
			"Tue" : [[goal:budgetGoalNewsRichard, data: [goalAccomplished: false, minutesBeyondGoal: 25, spread: [35 : 15, 36 : 10]]]],
			"Wed" : [[goal:budgetGoalNewsRichard, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocialRichard, data: [goalAccomplished: false, minutesBeyondGoal: 1, spread: [68 : 1]]]],
			"Thu" : [[goal:budgetGoalNewsRichard, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocialRichard, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [48 : 1]]]],
			"Fri" : [[goal:budgetGoalNewsRichard, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocialRichard, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [ : ]]]],
			"Sat" : [[goal:budgetGoalNewsRichard, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocialRichard, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [ : ]]]]]
		def expectedValuesBobLastWeek = [
			"Mon" : [],
			"Tue" : [],
			"Wed" : [],
			"Thu" : [[goal:budgetGoalSocialBob, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [80 : 15, 81 : 15, 82: 5]]]],
			"Fri" : [[goal:budgetGoalSocialBob, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalMultimediaBob, data: [goalAccomplished: false, minutesBeyondGoal: 1, spread: [60 : 1]]]],
			"Sat" : [[goal:budgetGoalSocialBob, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalMultimediaBob, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [84 : 1]]]]]
		def expectedValuesLastWeek = [[ user : richard, expectedValues: expectedValuesRichardLastWeek], [ user : bob, expectedValues: expectedValuesBobLastWeek]]

		def currentDayOfWeek = YonaServer.getCurrentDayOfWeek()
		def expectedTotalDays = 6 + currentDayOfWeek + 1
		def expectedTotalWeeks = 2

		when:
		def responseWeekOverviews = appService.getWeekActivityOverviews(richard)
		//get all days at once (max 2 weeks) to make assertion easy
		def responseDayOverviewsAll = appService.getDayActivityOverviews(richard, ["size": 14])
		//the min amount of days is 1 this week + 6 previous week, so we can safely get two normal pages
		def responseDayOverviewsPage1 = appService.getDayActivityOverviews(richard)
		def responseDayOverviewsPage2 = appService.getDayActivityOverviews(richard, ["page": 1])
		//get all days at once (max 2 weeks) to make assertion easy
		def responseDayOverviewsWithBuddies = appService.getDayActivityOverviewsWithBuddies(richard, ["size": 14])

		then:
		assertWeekOverviewBasics(responseWeekOverviews, [3, 2], expectedTotalWeeks)
		assertWeekDateForCurrentWeek(responseWeekOverviews)

		def weekOverviewLastWeek = responseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[1]
		assertNumberOfReportedDaysForGoalInWeekOverview(weekOverviewLastWeek, budgetGoalNewsRichard, 6)
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsRichard, expectedValuesRichardLastWeek, "Mon")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsRichard, expectedValuesRichardLastWeek, "Tue")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsRichard, expectedValuesRichardLastWeek, "Wed")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsRichard, expectedValuesRichardLastWeek, "Thu")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsRichard, expectedValuesRichardLastWeek, "Fri")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsRichard, expectedValuesRichardLastWeek, "Sat")

		assertWeekDetailForGoal(richard, weekOverviewLastWeek, budgetGoalNewsRichard, expectedValuesRichardLastWeek)

		assertNumberOfReportedDaysForGoalInWeekOverview(weekOverviewLastWeek, timeZoneGoalSocialRichard, 4)
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, "Wed")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, "Thu")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, "Fri")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, "Sat")

		assertDayOverviewBasics(responseDayOverviewsAll, expectedTotalDays, expectedTotalDays, 14)
		assertDayOverviewBasics(responseDayOverviewsPage1, 3, expectedTotalDays)
		assertDayOverviewBasics(responseDayOverviewsPage2, 3, expectedTotalDays)
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Mon")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Tue")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Wed")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Thu")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Fri")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Sat")

		assertDayOverviewForTimeZoneGoal(responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Wed")
		assertDayOverviewForTimeZoneGoal(responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Thu")
		assertDayOverviewForTimeZoneGoal(responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Fri")
		assertDayOverviewForTimeZoneGoal(responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Sat")

		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Mon")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Tue")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Wed")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Thu")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Fri")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsRichard, expectedValuesRichardLastWeek, 1, "Sat")

		assertDayDetail(richard, responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Wed")
		assertDayDetail(richard, responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Thu")
		assertDayDetail(richard, responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Fri")
		assertDayDetail(richard, responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Sat")

		assertDayOverviewWithBuddiesBasics(responseDayOverviewsWithBuddies, expectedTotalDays, expectedTotalDays, 14)
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, NEWS_ACT_CAT_URL, expectedValuesLastWeek, 1, "Mon")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, NEWS_ACT_CAT_URL, expectedValuesLastWeek, 1, "Tue")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, NEWS_ACT_CAT_URL, expectedValuesLastWeek, 1, "Wed")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, NEWS_ACT_CAT_URL, expectedValuesLastWeek, 1, "Thu")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, NEWS_ACT_CAT_URL, expectedValuesLastWeek, 1, "Fri")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, NEWS_ACT_CAT_URL, expectedValuesLastWeek, 1, "Sat")

		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, SOCIAL_ACT_CAT_URL, expectedValuesLastWeek, 1, "Wed")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, SOCIAL_ACT_CAT_URL, expectedValuesLastWeek, 1, "Thu")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, SOCIAL_ACT_CAT_URL, expectedValuesLastWeek, 1, "Fri")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, SOCIAL_ACT_CAT_URL, expectedValuesLastWeek, 1, "Sat")

		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, MULTIMEDIA_ACT_CAT_URL, expectedValuesLastWeek, 1, "Thu")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, MULTIMEDIA_ACT_CAT_URL, expectedValuesLastWeek, 1, "Fri")
		assertDayOverviewWithBuddies(responseDayOverviewsWithBuddies, richard, MULTIMEDIA_ACT_CAT_URL, expectedValuesLastWeek, 1, "Sat")

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Retrieve buddy activity report of previous week'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		User richard = richardAndBob.richard
		User bob = richardAndBob.bob

		addBudgetGoal(bob, SOCIAL_ACT_CAT_URL, 180, "W-1 Thu 18:00")
		reportAppActivity(bob, "Facebook", "W-1 Thu 20:00", "W-1 Thu 20:35")

		addTimeZoneGoal(bob, MULTIMEDIA_ACT_CAT_URL, ["20:00-22:00"], "W-1 Fri 14:00")
		reportNetworkActivity(bob, ["YouTube"], "http://www.youtube.com", "W-1 Fri 15:00")
		reportNetworkActivity(bob, ["YouTube"], "http://www.youtube.com", "W-1 Sat 21:00")

		richard = appService.reloadUser(richard)
		Goal budgetGoalSocialBob = richard.buddies[0].findActiveGoal(SOCIAL_ACT_CAT_URL)
		Goal timeZoneGoalMultimediaBob = richard.buddies[0].findActiveGoal(MULTIMEDIA_ACT_CAT_URL)

		def expectedValuesBobLastWeek = [
			"Mon" : [],
			"Tue" : [],
			"Wed" : [],
			"Thu" : [[goal:budgetGoalSocialBob, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [80 : 15, 81 : 15, 82: 5]]]],
			"Fri" : [[goal:budgetGoalSocialBob, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalMultimediaBob, data: [goalAccomplished: false, minutesBeyondGoal: 1, spread: [60 : 1]]]],
			"Sat" : [[goal:budgetGoalSocialBob, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalMultimediaBob, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [84 : 1]]]]]

		def currentDayOfWeek = YonaServer.getCurrentDayOfWeek()
		def expectedTotalDays = 6 + currentDayOfWeek + 1
		def expectedTotalWeeks = 2

		when:
		def responseWeekOverviews = appService.getWeekActivityOverviews(richard, richard.buddies[0])
		def responseDayOverviews = appService.getDayActivityOverviews(richard, richard.buddies[0], ["size": 14])

		then:
		// TODO: extend the below test to make it full fledged like the above one, preferably based on the same APIs
		responseWeekOverviews.status == 200
		responseWeekOverviews.responseData._embedded."yona:weekActivityOverviews".size() == 2
		def weekActivityOverview = responseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[1]
		def weekActivityForGoal = weekActivityOverview.weekActivities.find{ it._links."yona:goal".href == budgetGoalSocialBob.url}
		weekActivityForGoal.dayActivities["THURSDAY"]
		def dayActivityInWeekForGoal = weekActivityForGoal.dayActivities["THURSDAY"]
		dayActivityInWeekForGoal.spread == null // Only in detail
		dayActivityInWeekForGoal.totalActivityDurationMinutes == 35
		dayActivityInWeekForGoal.goalAccomplished
		dayActivityInWeekForGoal.totalMinutesBeyondGoal == 0
		dayActivityInWeekForGoal.date == null // Only on week overview level
		dayActivityInWeekForGoal.timeZoneId == null // Only on week overview level
		dayActivityInWeekForGoal._links."yona:goal" == null //already present on week
		dayActivityInWeekForGoal._links."yona:dayDetails"
		dayActivityInWeekForGoal._links.self == null  // This is not a top level or embedded resource

		responseDayOverviews.status == 200
		def dayOffset = YonaServer.relativeDateStringToDaysOffset(1, "Thu")
		def dayActivityOverview = responseDayOverviews.responseData._embedded."yona:dayActivityOverviews"[dayOffset]
		assert dayActivityOverview?.date =~ /\d{4}\-\d{2}\-\d{2}/
		assert dayActivityOverview.timeZoneId == "Europe/Amsterdam"
		assert dayActivityOverview.dayActivities?.size() == 1
		// YD-203 assert dayActivityOverview._links?.self?.href
		def dayActivityForGoal = dayActivityOverview.dayActivities.find{ it._links."yona:goal".href == budgetGoalSocialBob.url}
		assert dayActivityForGoal.totalActivityDurationMinutes == 35
		assert dayActivityForGoal.goalAccomplished
		assert dayActivityForGoal.totalMinutesBeyondGoal == 0
		assert dayActivityForGoal.date == null // Only on day overview level
		assert dayActivityForGoal.timeZoneId == null // Only on day overview level
		assert dayActivityForGoal._links."yona:dayDetails"
		assert dayActivityForGoal._links.self == null  // This is not a top level or embedded resource

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Add activity after retrieving the report'()
	{
		given:
		User richard = addRichard()
		def ZonedDateTime now = YonaServer.now
		Goal budgetGoalGambling = richard.findActiveGoal(GAMBLING_ACT_CAT_URL)
		Goal budgetGoalNews = richard.findActiveGoal(NEWS_ACT_CAT_URL)
		def currentShortDay = getCurrentShortDay(now)
		def initialExpectedValues = [
			(currentShortDay) : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:budgetGoalGambling, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [ : ]]]]]
		def initialResponseWeekOverviews = appService.getWeekActivityOverviews(richard)
		def initialResponseDayOverviews = appService.getDayActivityOverviews(richard)
		assert initialResponseWeekOverviews.status == 200
		def initialCurrentWeekOverview = initialResponseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[0]
		assertDayInWeekOverviewForGoal(initialCurrentWeekOverview, budgetGoalNews, initialExpectedValues, currentShortDay)
		assertWeekDetailForGoal(richard, initialCurrentWeekOverview, budgetGoalNews, initialExpectedValues)
		assertDayOverviewForBudgetGoal(initialResponseDayOverviews, budgetGoalNews, initialExpectedValues, 0, currentShortDay)
		assertDayDetail(richard, initialResponseDayOverviews, budgetGoalNews, initialExpectedValues, 0, currentShortDay)

		when:
		reportAppActivities(richard, AppActivity.singleActivity("NU.nl", now, now))

		then:
		def expectedValuesAfterActivity = [
			(currentShortDay) : [[goal:budgetGoalNews, data: [goalAccomplished: false, minutesBeyondGoal: 1, spread: [(getCurrentSpreadCell(now)) : 1]]], [goal:budgetGoalGambling, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [ : ]]]]]
		def responseWeekOverviewsAfterActivity = appService.getWeekActivityOverviews(richard)
		def currentWeekOverviewAfterActivity = responseWeekOverviewsAfterActivity.responseData._embedded."yona:weekActivityOverviews"[0]
		assertDayInWeekOverviewForGoal(currentWeekOverviewAfterActivity, budgetGoalNews, expectedValuesAfterActivity, currentShortDay)
		assertWeekDetailForGoal(richard, currentWeekOverviewAfterActivity, budgetGoalNews, expectedValuesAfterActivity)

		def responseDayOverviewsAfterActivity = appService.getDayActivityOverviews(richard)
		assertDayOverviewForBudgetGoal(responseDayOverviewsAfterActivity, budgetGoalNews, expectedValuesAfterActivity, 0, currentShortDay)
		assertDayDetail(richard, responseDayOverviewsAfterActivity, budgetGoalNews, expectedValuesAfterActivity, 0, currentShortDay)

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Add activity after retrieving the report with multiple days'()
	{
		given:
		User richard = addRichard()
		def ZonedDateTime now = YonaServer.now
		setGoalCreationTime(richard, NEWS_ACT_CAT_URL, "W-1 Mon 02:18")
		Goal budgetGoalGambling = richard.findActiveGoal(GAMBLING_ACT_CAT_URL)
		Goal budgetGoalNews = richard.findActiveGoal(NEWS_ACT_CAT_URL)
		def initialExpectedValuesLastWeek = [
			"Mon" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Tue" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Wed" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Thu" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Fri" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Sat" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
		]
		def initialResponseWeekOverviews = appService.getWeekActivityOverviews(richard)
		def initialResponseDayOverviews = appService.getDayActivityOverviews(richard, ["size": 14])
		assert initialResponseWeekOverviews.status == 200
		def initialCurrentWeekOverviewLastWeek = initialResponseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[1]
		assertDayInWeekOverviewForGoal(initialCurrentWeekOverviewLastWeek, budgetGoalNews, initialExpectedValuesLastWeek, "Wed")
		assertWeekDetailForGoal(richard, initialCurrentWeekOverviewLastWeek, budgetGoalNews, initialExpectedValuesLastWeek)
		assertDayOverviewForBudgetGoal(initialResponseDayOverviews, budgetGoalNews, initialExpectedValuesLastWeek, 0, "Wed")
		assertDayDetail(richard, initialResponseDayOverviews, budgetGoalNews, initialExpectedValuesLastWeek, 0, "Wed")

		when:
		reportAppActivity(richard, "NU.nl", "W-1 Wed 03:15", "W-1 Wed 03:35")

		then:
		def expectedValuesLastWeekAfterActivity = [
			"Mon" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Tue" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Wed" : [[goal:budgetGoalNews, data: [goalAccomplished: false, minutesBeyondGoal: 20, spread: [13 : 15, 14 : 5]]]],
			"Thu" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Fri" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Sat" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
		]
		def responseWeekOverviewsAfterActivity = appService.getWeekActivityOverviews(richard)
		def currentWeekOverviewAfterActivity = responseWeekOverviewsAfterActivity.responseData._embedded."yona:weekActivityOverviews"[1]
		assertDayInWeekOverviewForGoal(currentWeekOverviewAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity, "Tue")
		assertDayInWeekOverviewForGoal(currentWeekOverviewAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity, "Wed")
		assertDayInWeekOverviewForGoal(currentWeekOverviewAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity, "Thu")
		assertWeekDetailForGoal(richard, currentWeekOverviewAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity)

		def responseDayOverviewsAfterActivity = appService.getDayActivityOverviews(richard, ["size": 14])
		assertDayOverviewForBudgetGoal(responseDayOverviewsAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity, 1, "Tue")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity, 1, "Wed")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity, 1, "Thu")
		assertDayDetail(richard, responseDayOverviewsAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity, 1, "Tue")
		assertDayDetail(richard, responseDayOverviewsAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity, 1, "Wed")
		assertDayDetail(richard, responseDayOverviewsAfterActivity, budgetGoalNews, expectedValuesLastWeekAfterActivity, 1, "Thu")

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Remove goal after adding activities'()
	{
		given:
		User richard = addRichard()
		setGoalCreationTime(richard, NEWS_ACT_CAT_URL, "W-1 Mon 02:18")
		reportAppActivity(richard, "NU.nl", "W-1 Mon 03:15", "W-1 Mon 03:35")
		reportAppActivities(richard, [createAppActivity("NU.nl", "W-1 Tue 08:45", "W-1 Tue 09:10"), createAppActivity("Facebook", "W-1 Tue 09:35", "W-1 Tue 10:10")])
		addTimeZoneGoal(richard, SOCIAL_ACT_CAT_URL, ["11:00-12:00"], "W-1 Wed 13:55")
		reportNetworkActivity(richard, ["social"], "http://www.facebook.com", "W-1 Wed 15:00")
		reportNetworkActivity(richard, ["social"], "http://www.facebook.com", "W-1 Thu 11:30")

		richard = appService.reloadUser(richard)
		Goal budgetGoalNews = richard.findActiveGoal(NEWS_ACT_CAT_URL)
		Goal timeZoneGoalSocial = richard.findActiveGoal(SOCIAL_ACT_CAT_URL)

		def expectedValuesLastWeekBeforeDelete = [
			"Mon" : [[goal:budgetGoalNews, data: [goalAccomplished: false, minutesBeyondGoal: 20, spread: [13 : 15, 14 : 5]]]],
			"Tue" : [[goal:budgetGoalNews, data: [goalAccomplished: false, minutesBeyondGoal: 25, spread: [35 : 15, 36 : 10]]]],
			"Wed" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocial, data: [goalAccomplished: false, minutesBeyondGoal: 1, spread: [68 : 1]]]],
			"Thu" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocial, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [48 : 1]]]],
			"Fri" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocial, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [ : ]]]],
			"Sat" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocial, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [ : ]]]]]
		def expectedGoalsPerWeekBeforeDelete = [3, 2]

		def currentDayOfWeek = YonaServer.getCurrentDayOfWeek()
		def expectedTotalDaysBeforeDelete = 6 + currentDayOfWeek + 1
		def expectedTotalWeeksBeforeDelete = 2
		def responseWeekOverviewsBeforeDelete = appService.getWeekActivityOverviews(richard)
		//get all days at once (max 2 weeks) to make assertion easy
		def responseDayOverviewsAllBeforeDelete = appService.getDayActivityOverviews(richard, ["size": 14])

		assertWeekOverviewBasics(responseWeekOverviewsBeforeDelete, expectedGoalsPerWeekBeforeDelete, expectedTotalWeeksBeforeDelete)
		def weekOverviewLastWeekBeforeDelete = responseWeekOverviewsBeforeDelete.responseData._embedded."yona:weekActivityOverviews"[1]
		assertNumberOfReportedDaysForGoalInWeekOverview(weekOverviewLastWeekBeforeDelete, budgetGoalNews, 6)
		assertDayInWeekOverviewForGoal(weekOverviewLastWeekBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete, "Mon")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeekBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete, "Tue")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeekBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete, "Wed")

		assertWeekDetailForGoal(richard, weekOverviewLastWeekBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete)

		assertDayOverviewBasics(responseDayOverviewsAllBeforeDelete, expectedTotalDaysBeforeDelete, expectedTotalDaysBeforeDelete, 14)
		assertDayOverviewForBudgetGoal(responseDayOverviewsAllBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete, 1, "Mon")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAllBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete, 1, "Tue")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAllBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete, 1, "Wed")

		assertDayDetail(richard, responseDayOverviewsAllBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete, 1, "Mon")
		assertDayDetail(richard, responseDayOverviewsAllBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete, 1, "Tue")
		assertDayDetail(richard, responseDayOverviewsAllBeforeDelete, budgetGoalNews, expectedValuesLastWeekBeforeDelete, 1, "Wed")

		assertNumberOfReportedDaysForGoalInWeekOverview(weekOverviewLastWeekBeforeDelete, timeZoneGoalSocial, 4)
		assertDayInWeekOverviewForGoal(weekOverviewLastWeekBeforeDelete, timeZoneGoalSocial, expectedValuesLastWeekBeforeDelete, "Wed")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeekBeforeDelete, timeZoneGoalSocial, expectedValuesLastWeekBeforeDelete, "Thu")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeekBeforeDelete, timeZoneGoalSocial, expectedValuesLastWeekBeforeDelete, "Fri")

		assertDayDetail(richard, responseDayOverviewsAllBeforeDelete, timeZoneGoalSocial, expectedValuesLastWeekBeforeDelete, 1, "Wed")
		assertDayDetail(richard, responseDayOverviewsAllBeforeDelete, timeZoneGoalSocial, expectedValuesLastWeekBeforeDelete, 1, "Thu")
		assertDayDetail(richard, responseDayOverviewsAllBeforeDelete, timeZoneGoalSocial, expectedValuesLastWeekBeforeDelete, 1, "Fri")

		when:
		def response = appService.removeGoal(richard, budgetGoalNews)

		then:
		def expectedTotalDaysAfterDelete = expectedTotalDaysBeforeDelete - 2
		def expectedTotalWeeksAfterDelete = expectedTotalWeeksBeforeDelete
		def expectedValuesLastWeekAfterDelete = [
			"Mon" : [[goal:budgetGoalNews, data: [goalAccomplished: false, minutesBeyondGoal: 20, spread: [13 : 15, 14 : 5]]]],
			"Tue" : [[goal:budgetGoalNews, data: [goalAccomplished: false, minutesBeyondGoal: 25, spread: [35 : 15, 36 : 10]]]],
			"Wed" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocial, data: [goalAccomplished: false, minutesBeyondGoal: 1, spread: [68 : 1]]]],
			"Thu" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocial, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [48 : 1]]]],
			"Fri" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocial, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [ : ]]]],
			"Sat" : [[goal:budgetGoalNews, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocial, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [ : ]]]]]
		def expectedGoalsPerWeekAfterDelete = expectedGoalsPerWeekBeforeDelete.collect{it - 1}

		//get all days at once (max 2 weeks) to make assertion easy
		def responseWeekOverviewsAfterDelete = appService.getWeekActivityOverviews(richard)
		assertWeekOverviewBasics(responseWeekOverviewsAfterDelete, expectedGoalsPerWeekAfterDelete, expectedTotalWeeksAfterDelete)

		def weekOverviewLastWeekAfterDelete = responseWeekOverviewsAfterDelete.responseData._embedded."yona:weekActivityOverviews"[1]
		assertNumberOfReportedDaysForGoalInWeekOverview(weekOverviewLastWeekAfterDelete, timeZoneGoalSocial, 4)
		assertDayInWeekOverviewForGoal(weekOverviewLastWeekAfterDelete, timeZoneGoalSocial, expectedValuesLastWeekAfterDelete, "Wed")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeekAfterDelete, timeZoneGoalSocial, expectedValuesLastWeekAfterDelete, "Thu")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeekAfterDelete, timeZoneGoalSocial, expectedValuesLastWeekAfterDelete, "Fri")

		def responseDayOverviewsAllAfterDelete = appService.getDayActivityOverviews(richard, ["size": 14])
		assertDayOverviewBasics(responseDayOverviewsAllAfterDelete, expectedTotalDaysAfterDelete, expectedTotalDaysAfterDelete, 14)
		assertDayDetail(richard, responseDayOverviewsAllAfterDelete, timeZoneGoalSocial, expectedValuesLastWeekAfterDelete, 1, "Wed")
		assertDayDetail(richard, responseDayOverviewsAllAfterDelete, timeZoneGoalSocial, expectedValuesLastWeekAfterDelete, 1, "Thu")
		assertDayDetail(richard, responseDayOverviewsAllAfterDelete, timeZoneGoalSocial, expectedValuesLastWeekAfterDelete, 1, "Fri")

		// See whether we can still report activities the activity category of the deleted goal
		reportAppActivity(richard, "NU.nl", "W-1 Mon 03:15", "W-1 Mon 03:35")

		// Activity report should be identical
		def responseWeekOverviewsNewActivity = appService.getWeekActivityOverviews(richard)
		assertWeekOverviewBasics(responseWeekOverviewsNewActivity, expectedGoalsPerWeekAfterDelete, expectedTotalWeeksAfterDelete)

		def weekOverviewLastWeekAfterNewActivity = responseWeekOverviewsNewActivity.responseData._embedded."yona:weekActivityOverviews"[1]
		assertNumberOfReportedDaysForGoalInWeekOverview(weekOverviewLastWeekAfterNewActivity, timeZoneGoalSocial, 4)

		def responseDayOverviewsAllAfterNewActivity = appService.getDayActivityOverviews(richard, ["size": 14])
		assertDayOverviewBasics(responseDayOverviewsAllAfterNewActivity, expectedTotalDaysAfterDelete, expectedTotalDaysAfterDelete, 14)

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Add goals and fetch activities'()
	{
		given:
		User richard = addRichard()
		addTimeZoneGoal(richard, MULTIMEDIA_ACT_CAT_URL, ["11:00-12:00"])
		assert appService.getDayActivityOverviews(richard).status == 200
		assert appService.getWeekActivityOverviews(richard).status == 200

		when:
		addBudgetGoal(richard, COMMUNICATION_ACT_CAT_URL, 60)
		then:
		appService.getDayActivityOverviews(richard).status == 200
		appService.getWeekActivityOverviews(richard).status == 200

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Retrieve inactive activity report before and after update of time zone goal'()
	{
		given:
		User richard = addRichard()
		Goal timeZoneGoalMultimediaRichard = addTimeZoneGoal(richard, MULTIMEDIA_ACT_CAT_URL, ["11:00-12:00"])
		assert appService.getDayActivityOverviews(richard).status == 200
		assert appService.getWeekActivityOverviews(richard).status == 200

		when:
		updateTimeZoneGoal(richard, timeZoneGoalMultimediaRichard, ["13:00-14:00"])

		then:
		appService.getDayActivityOverviews(richard).status == 200
		appService.getWeekActivityOverviews(richard).status == 200

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Retrieve inactive activity report before and after update of budget goal'()
	{
		given:
		User richard = addRichard()
		Goal budgetGoalMultimediaRichard = addBudgetGoal(richard, MULTIMEDIA_ACT_CAT_URL, 60)
		assert appService.getDayActivityOverviews(richard).status == 200
		assert appService.getWeekActivityOverviews(richard).status == 200

		when:
		updateBudgetGoal(richard, budgetGoalMultimediaRichard, 81)

		then:
		appService.getDayActivityOverviews(richard).status == 200
		appService.getWeekActivityOverviews(richard).status == 200

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Retrieve activity report after goal update'()
	{
		given:
		User richard = addRichard()

		// Week -2
		// Monday
		setGoalCreationTime(richard, NEWS_ACT_CAT_URL, "W-2 Mon 02:18")
		Goal budgetGoalNewsBeforeUpdate = richard.findActiveGoal(NEWS_ACT_CAT_URL)
		reportAppActivity(richard, "NU.nl", "W-2 Mon 03:15", "W-2 Mon 03:35")

		// Tuesday
		reportAppActivities(richard, [createAppActivity("NU.nl", "W-2 Tue 08:45", "W-2 Tue 09:10"), createAppActivity("Facebook", "W-2 Tue 09:35", "W-2 Tue 10:10")])

		// Week -1
		// Wednesday
		reportAppActivity(richard, "NU.nl", "W-1 Wed 09:13", "W-1 Wed 10:07")
		updateBudgetGoal(richard, budgetGoalNewsBeforeUpdate, 60, "W-1 Wed 20:51")
		richard = appService.reloadUser(richard)
		budgetGoalNewsBeforeUpdate = richard.goals.find{ it.activityCategoryUrl == NEWS_ACT_CAT_URL }
		reportAppActivity(richard, "NU.nl", "W-1 Wed 21:43", "W-1 Wed 22:01")

		addTimeZoneGoal(richard, SOCIAL_ACT_CAT_URL, ["11:00-12:00"], "W-1 Thu 13:55")
		reportNetworkActivity(richard, ["social"], "http://www.facebook.com", "W-1 Thu 15:00")
		reportNetworkActivity(richard, ["social"], "http://www.facebook.com", "W-1 Fri 11:30")

		richard = appService.reloadUser(richard)
		Goal budgetGoalNewsAfterUpdate = richard.findActiveGoal(NEWS_ACT_CAT_URL)
		Goal timeZoneGoalSocialRichard = richard.findActiveGoal(SOCIAL_ACT_CAT_URL)

		def expectedValuesRichardWeekBeforeLastWeek = [
			"Mon" : [[goal:budgetGoalNewsBeforeUpdate, data: [goalAccomplished: false, minutesBeyondGoal: 20, spread: [13 : 15, 14 : 5]]]],
			"Tue" : [[goal:budgetGoalNewsBeforeUpdate, data: [goalAccomplished: false, minutesBeyondGoal: 25, spread: [35 : 15, 36 : 10]]]],
			"Wed" : [[goal:budgetGoalNewsBeforeUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Thu" : [[goal:budgetGoalNewsBeforeUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Fri" : [[goal:budgetGoalNewsBeforeUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Sat" : [[goal:budgetGoalNewsBeforeUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]]]
		def expectedValuesRichardLastWeek = [
			"Sun" : [[goal:budgetGoalNewsBeforeUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Mon" : [[goal:budgetGoalNewsBeforeUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Tue" : [[goal:budgetGoalNewsBeforeUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]]],
			"Wed" : [[goal:budgetGoalNewsAfterUpdate, data: [goalAccomplished: false, minutesBeyondGoal: 12, spread: [36 : 2, 37 : 15, 38 : 15, 39 : 15, 40: 7, 86 : 2, 87 : 15, 88 : 1]]]],
			"Thu" : [[goal:budgetGoalNewsAfterUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocialRichard, data: [goalAccomplished: false, minutesBeyondGoal: 1, spread: [68 : 1]]]],
			"Fri" : [[goal:budgetGoalNewsAfterUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocialRichard, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [48 : 1]]]],
			"Sat" : [[goal:budgetGoalNewsAfterUpdate, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: []]], [goal:timeZoneGoalSocialRichard, data: [goalAccomplished: true, minutesBeyondGoal: 0, spread: [ : ]]]]]

		def currentDayOfWeek = YonaServer.getCurrentDayOfWeek()
		def expectedTotalDays = 6 + 7 + currentDayOfWeek + 1
		def expectedTotalWeeks = 3

		when:
		def responseWeekOverviews = appService.getWeekActivityOverviews(richard, ["size": expectedTotalWeeks])
		//get all days at once (max 2 weeks) to make assertion easy
		def responseDayOverviewsAll = appService.getDayActivityOverviews(richard, ["size": 21])

		then:
		assertWeekOverviewBasics(responseWeekOverviews, [3, 2, 1], expectedTotalWeeks, expectedTotalWeeks)
		assertWeekDateForCurrentWeek(responseWeekOverviews)

		def weekOverviewWeekBeforeLastWeek = responseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[2]
		assertNumberOfReportedDaysForGoalInWeekOverview(weekOverviewWeekBeforeLastWeek, budgetGoalNewsBeforeUpdate, 6)
		assertDayInWeekOverviewForGoal(weekOverviewWeekBeforeLastWeek, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, "Mon")
		assertDayInWeekOverviewForGoal(weekOverviewWeekBeforeLastWeek, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, "Tue")
		assertDayInWeekOverviewForGoal(weekOverviewWeekBeforeLastWeek, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, "Wed")
		assertDayInWeekOverviewForGoal(weekOverviewWeekBeforeLastWeek, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, "Thu")
		assertDayInWeekOverviewForGoal(weekOverviewWeekBeforeLastWeek, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, "Fri")
		assertDayInWeekOverviewForGoal(weekOverviewWeekBeforeLastWeek, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, "Sat")

		def weekOverviewLastWeek = responseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[1]
		assertNumberOfReportedDaysForGoalInWeekOverview(weekOverviewLastWeek, budgetGoalNewsAfterUpdate, 7)
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsBeforeUpdate, expectedValuesRichardLastWeek, "Sun")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsBeforeUpdate, expectedValuesRichardLastWeek, "Mon")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsBeforeUpdate, expectedValuesRichardLastWeek, "Tue")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, "Wed")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, "Thu")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, "Fri")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, "Sat")

		assertWeekDetailForGoal(richard, weekOverviewLastWeek, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek)

		assertNumberOfReportedDaysForGoalInWeekOverview(weekOverviewLastWeek, timeZoneGoalSocialRichard, 3)
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, "Thu")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, "Fri")
		assertDayInWeekOverviewForGoal(weekOverviewLastWeek, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, "Sat")

		assertDayOverviewBasics(responseDayOverviewsAll, expectedTotalDays, expectedTotalDays, 21)
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Mon")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Tue")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Wed")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Thu")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Fri")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Sat")

		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardLastWeek, 1, "Sun")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardLastWeek, 1, "Mon")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardLastWeek, 1, "Tue")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, 1, "Wed")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, 1, "Thu")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, 1, "Fri")
		assertDayOverviewForBudgetGoal(responseDayOverviewsAll, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, 1, "Sat")

		assertDayOverviewForTimeZoneGoal(responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Thu")
		assertDayOverviewForTimeZoneGoal(responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Fri")
		assertDayOverviewForTimeZoneGoal(responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Sat")

		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardLastWeek, 1, "Mon")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardLastWeek, 1, "Tue")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, 1, "Wed")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, 1, "Thu")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, 1, "Fri")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsAfterUpdate, expectedValuesRichardLastWeek, 1, "Sat")

		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Mon")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Tue")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Wed")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Thu")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Fri")
		assertDayDetail(richard, responseDayOverviewsAll, budgetGoalNewsBeforeUpdate, expectedValuesRichardWeekBeforeLastWeek, 2, "Sat")

		assertDayDetail(richard, responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Thu")
		assertDayDetail(richard, responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Fri")
		assertDayDetail(richard, responseDayOverviewsAll, timeZoneGoalSocialRichard, expectedValuesRichardLastWeek, 1, "Sat")

		cleanup:
		appService.deleteUser(richard)
	}

	def 'Comment on buddy day activity'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		User richard = richardAndBob.richard
		User bob = richardAndBob.bob

		when:
		setGoalCreationTime(bob, NEWS_ACT_CAT_URL, "W-1 Mon 02:18")

		richard = appService.reloadUser(richard)
		bob = appService.reloadUser(bob)

		then:
		assertCommentingWorks(richard, bob, false, {user -> appService.getDayActivityOverviews(user, ["size": 14])},
		{user -> appService.getDayActivityOverviews(user, user.buddies[0], ["size": 14])},
		{responseOverviews, user, goal -> getDayDetails(responseOverviews, user, goal, 1, "Tue")})

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Comment on buddy week activity'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		User richard = richardAndBob.richard
		User bob = richardAndBob.bob

		when:
		setGoalCreationTime(bob, NEWS_ACT_CAT_URL, "W-1 Mon 02:18")

		richard = appService.reloadUser(richard)
		bob = appService.reloadUser(bob)

		then:
		assertCommentingWorks(richard, bob, true, {user -> appService.getWeekActivityOverviews(user, ["size": 14])},
		{user -> appService.getWeekActivityOverviews(user, user.buddies[0], ["size": 14])},
		{responseOverviews, user, goal -> getWeekDetails(responseOverviews, user, goal, 1)})

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Richard retrieves buddy activity info before Bob accepted Richard\'s buddy request'()
	{
		given:
		User richard = addRichard()
		User bob = addBob()
		appService.sendBuddyConnectRequest(richard, bob)
		richard = appService.reloadUser(richard)

		when:
		def responseDayOverviewsWithBuddies = appService.getDayActivityOverviewsWithBuddies(richard, ["size": 14])

		then:
		richard.buddies[0].dailyActivityReportsUrl == null
		richard.buddies[0].weeklyActivityReportsUrl == null
		responseDayOverviewsWithBuddies.status == 200
		responseDayOverviewsWithBuddies.responseData._embedded."yona:dayActivityOverviews"[0].dayActivities.find{ it._links."yona:activityCategory"?.href == GAMBLING_ACT_CAT_URL}.dayActivitiesForUsers.size() == 1

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Richard retrieves buddy activity info before he processed Bob\'s acceptance'()
	{
		given:
		User richard = addRichard()
		User bob = addBob()
		appService.sendBuddyConnectRequest(richard, bob)
		def connectRequestMessage = appService.fetchBuddyConnectRequestMessage(bob)
		def acceptURL = connectRequestMessage.acceptURL
		assert appService.postMessageActionWithPassword(acceptURL, ["message" : "Yes, great idea!"], bob.password).status == 200
		richard = appService.reloadUser(richard)

		when:
		def responseDayOverviewsWithBuddies = appService.getDayActivityOverviewsWithBuddies(richard, ["size": 14])

		then:
		richard.buddies[0].dailyActivityReportsUrl == null
		richard.buddies[0].weeklyActivityReportsUrl == null
		responseDayOverviewsWithBuddies.status == 200
		responseDayOverviewsWithBuddies.responseData._embedded."yona:dayActivityOverviews"[0].dayActivities.find{ it._links."yona:activityCategory"?.href == GAMBLING_ACT_CAT_URL}.dayActivitiesForUsers.size() == 1

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Bob retrieves buddy activity info before Richard processed Bob\'s acceptance'()
	{
		given:
		User richard = addRichard()
		User bob = addBob()
		appService.sendBuddyConnectRequest(richard, bob)
		def connectRequestMessage = appService.fetchBuddyConnectRequestMessage(bob)
		def acceptURL = connectRequestMessage.acceptURL
		assert appService.postMessageActionWithPassword(acceptURL, ["message" : "Yes, great idea!"], bob.password).status == 200
		richard = appService.reloadUser(richard)
		bob = appService.reloadUser(bob)
		Goal budgetGoalGamblingRichard = bob.buddies[0].findActiveGoal(GAMBLING_ACT_CAT_URL)

		when:
		def responseDayOverviewsWithBuddies = appService.getDayActivityOverviewsWithBuddies(bob, ["size": 14])

		then:
		responseDayOverviewsWithBuddies.status == 200
		responseDayOverviewsWithBuddies.responseData._embedded."yona:dayActivityOverviews"[0].dayActivities.find{ it._links."yona:activityCategory"?.href == GAMBLING_ACT_CAT_URL}.dayActivitiesForUsers.size() == 2
		def responseWeekOverviews = appService.getWeekActivityOverviews(bob, bob.buddies[0])
		responseWeekOverviews.status == 200
		responseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[0].weekActivities.find{ it._links."yona:goal".href == budgetGoalGamblingRichard.url}
		def weekActivityForGoal = responseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[0].weekActivities.find{ it._links."yona:goal".href == budgetGoalGamblingRichard.url}
		def dayActivityInWeekForGoal = weekActivityForGoal.dayActivities[YonaServer.now.dayOfWeek.toString()]
		dayActivityInWeekForGoal.totalActivityDurationMinutes == 0

		def responseDayOverviews = appService.getDayActivityOverviews(bob, bob.buddies[0])

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	def 'Richard retrieves buddy activity info before he processed Bob\'s disconnect'()
	{
		given:
		def richardAndBob = addRichardAndBobAsBuddies()
		User richard = richardAndBob.richard
		User bob = richardAndBob.bob
		def responseRemoveBuddy = appService.removeBuddy(bob, appService.getBuddies(bob)[0], "Sorry, I regret having asked you")
		assert responseRemoveBuddy.status == 200

		when:
		def responseDayOverviewsWithBuddies = appService.getDayActivityOverviewsWithBuddies(richard, ["size": 14])

		then:
		responseDayOverviewsWithBuddies.status == 200
		responseDayOverviewsWithBuddies.responseData._embedded."yona:dayActivityOverviews"[0].dayActivities.find{ it._links."yona:activityCategory"?.href == GAMBLING_ACT_CAT_URL}.dayActivitiesForUsers.size() == 1

		cleanup:
		appService.deleteUser(richard)
		appService.deleteUser(bob)
	}

	private void assertCommentingWorks(User richard, User bob, boolean isWeek, Closure userOverviewRetriever, Closure buddyOverviewRetriever, Closure detailsRetriever)
	{
		Goal budgetGoalNewsBuddyBob = richard.buddies[0].findActiveGoal(NEWS_ACT_CAT_URL)
		Goal budgetGoalNewsBob = bob.findActiveGoal(NEWS_ACT_CAT_URL)

		def responseOverviewsBobAsBuddyAll = buddyOverviewRetriever(richard)
		assert responseOverviewsBobAsBuddyAll.status == 200

		def responseDetailsBobAsBuddy = detailsRetriever(responseOverviewsBobAsBuddyAll, richard, budgetGoalNewsBuddyBob)
		assert responseDetailsBobAsBuddy.responseData._links."yona:addComment".href
		assert responseDetailsBobAsBuddy.responseData._links."yona:messages".href

		def message = """{"message": "You're quiet!"}"""
		def responseAddMessage = appService.yonaServer.createResourceWithPassword(responseDetailsBobAsBuddy.responseData._links."yona:addComment".href, message, richard.password)

		assert responseAddMessage.status == 200
		def addedMessage = responseAddMessage.responseData
		assertCommentMessageDetails(addedMessage, richard, isWeek, richard, responseDetailsBobAsBuddy.responseData._links.self.href, "You're quiet!")

		def responseInitialGetCommentMessagesSeenByRichard = getActivityDetailMessages(responseDetailsBobAsBuddy, richard, 1)
		def initialMessageSeenByRichard = responseInitialGetCommentMessagesSeenByRichard.responseData._embedded."yona:messages"[0]
		assertCommentMessageDetails(initialMessageSeenByRichard, richard, isWeek, richard, responseDetailsBobAsBuddy.responseData._links.self.href, "You're quiet!")

		def responseOverviewsBobAll = userOverviewRetriever(bob)
		assert responseOverviewsBobAll.status == 200

		def responseDetailsBob = detailsRetriever(responseOverviewsBobAll, bob, budgetGoalNewsBob)
		assert responseDetailsBob.responseData._links."yona:addComment" == null
		assert responseDetailsBob.responseData._links."yona:messages".href

		def responseInitialGetCommentMessagesSeenByBob = getActivityDetailMessages(responseDetailsBob, bob, 1)
		def initialMessageSeenByBob = responseInitialGetCommentMessagesSeenByBob.responseData._embedded."yona:messages"[0]
		assertCommentMessageDetails(initialMessageSeenByBob, bob, isWeek, bob.buddies[0], responseDetailsBob.responseData._links.self.href, "You're quiet!")

		replyToMessage(initialMessageSeenByBob, bob, "My battery died :)", isWeek, responseDetailsBob)

		def responseSecondGetCommentMessagesSeenByRichard = getActivityDetailMessages(responseDetailsBobAsBuddy, richard, 2)
		def replyMessageSeenByRichard = responseSecondGetCommentMessagesSeenByRichard.responseData._embedded."yona:messages"[1]
		assertCommentMessageDetails(replyMessageSeenByRichard, richard, isWeek, richard.buddies[0], responseDetailsBobAsBuddy.responseData._links.self.href, "My battery died :)",
				responseSecondGetCommentMessagesSeenByRichard.responseData._embedded."yona:messages"[0])

		replyToMessage(replyMessageSeenByRichard, richard, "Too bad!", isWeek, responseDetailsBobAsBuddy)

		def responseSecondGetCommentMessagesSeenByBob = getActivityDetailMessages(responseDetailsBob, bob, 3)
		def secondReplyMessageSeenByBob = responseSecondGetCommentMessagesSeenByBob.responseData._embedded."yona:messages"[2]
		assertCommentMessageDetails(secondReplyMessageSeenByBob, bob, isWeek, bob.buddies[0], responseDetailsBob.responseData._links.self.href, "Too bad!")

		replyToMessage(secondReplyMessageSeenByBob, bob, "Yes, it is...", isWeek, responseDetailsBob)

		def responseThirdGetCommentMessagesSeenByRichard = getActivityDetailMessages(responseDetailsBobAsBuddy, richard, 4)
		def replyToReplyMessageSeenByRichard = responseThirdGetCommentMessagesSeenByRichard.responseData._embedded."yona:messages"[3]
		assertCommentMessageDetails(replyToReplyMessageSeenByRichard, richard, isWeek, richard.buddies[0], responseDetailsBobAsBuddy.responseData._links.self.href, "Yes, it is...")

		replyToMessage(replyToReplyMessageSeenByRichard, richard, "No budget for a new one?", isWeek, responseDetailsBobAsBuddy)

		def responseFinalGetCommentMessagesSeenByRichard = getActivityDetailMessages(responseDetailsBobAsBuddy, richard, 5)
		def messagesRichard = responseFinalGetCommentMessagesSeenByRichard.responseData._embedded."yona:messages"
		assertCommentMessageDetails(messagesRichard[0], richard, isWeek, richard, responseDetailsBobAsBuddy.responseData._links.self.href, "You're quiet!")
		assertCommentMessageDetails(messagesRichard[1], richard, isWeek, richard.buddies[0], responseDetailsBobAsBuddy.responseData._links.self.href, "My battery died :)", messagesRichard[0])
		assertCommentMessageDetails(messagesRichard[2], richard, isWeek, richard, responseDetailsBobAsBuddy.responseData._links.self.href, "Too bad!", messagesRichard[1])
		assertCommentMessageDetails(messagesRichard[3], richard, isWeek, richard.buddies[0], responseDetailsBobAsBuddy.responseData._links.self.href, "Yes, it is...", messagesRichard[2])
		assertNextPage(responseFinalGetCommentMessagesSeenByRichard, richard)

		def responseFinalGetCommentMessagesSeenByBob = getActivityDetailMessages(responseDetailsBob, bob, 5)
		def messagesBob = responseFinalGetCommentMessagesSeenByBob.responseData._embedded."yona:messages"
		assertCommentMessageDetails(messagesBob[0], bob, isWeek, bob.buddies[0], responseDetailsBob.responseData._links.self.href, "You're quiet!")
		assertCommentMessageDetails(messagesBob[1], bob, isWeek, bob, responseDetailsBob.responseData._links.self.href, "My battery died :)", messagesBob[0])
		assertCommentMessageDetails(messagesBob[2], bob, isWeek, bob.buddies[0], responseDetailsBob.responseData._links.self.href, "Too bad!", messagesBob[1])
		assertCommentMessageDetails(messagesBob[3], bob, isWeek, bob, responseDetailsBob.responseData._links.self.href, "Yes, it is...", messagesBob[2])
		assertNextPage(responseFinalGetCommentMessagesSeenByBob, bob)

		def allMessagesRichardResponse = appService.getMessages(richard)
		assert allMessagesRichardResponse.responseData._embedded?."yona:messages".findAll{ it."@type" == "ActivityCommentMessage"}.size() == 2
		def activityCommentMessagesRichard = allMessagesRichardResponse.responseData._embedded?."yona:messages".findAll{ it."@type" == "ActivityCommentMessage"}
		assertCommentMessageDetails(activityCommentMessagesRichard[0], richard, isWeek, richard.buddies[0], responseDetailsBobAsBuddy.responseData._links.self.href, "Yes, it is...")
		assertCommentMessageDetails(activityCommentMessagesRichard[1], richard, isWeek, richard.buddies[0], responseDetailsBobAsBuddy.responseData._links.self.href, "My battery died :)")

		def allMessagesBobResponse = appService.getMessages(bob)
		assert allMessagesBobResponse.responseData._embedded?."yona:messages".findAll{ it."@type" == "ActivityCommentMessage"}.size() == 3
		def activityCommentMessagesBob = allMessagesBobResponse.responseData._embedded?."yona:messages".findAll{ it."@type" == "ActivityCommentMessage"}
		assertCommentMessageDetails(activityCommentMessagesBob[0], bob, isWeek, bob.buddies[0], responseDetailsBob.responseData._links.self.href, "No budget for a new one?")
		assertCommentMessageDetails(activityCommentMessagesBob[1], bob, isWeek, bob.buddies[0], responseDetailsBob.responseData._links.self.href, "Too bad!")
		assertCommentMessageDetails(activityCommentMessagesBob[2], bob, isWeek, bob.buddies[0], responseDetailsBob.responseData._links.self.href, "You're quiet!")
	}

	private void assertWeekDateForCurrentWeek(responseWeekOverviews)
	{
		// Java treats Sunday as last day of the week while Yona treats it as first.
		// For that reason, we ignore mismatches when they occur on a Sunday.
		boolean isSunday = YonaServer.now.getDayOfWeek() == DayOfWeek.SUNDAY
		DateTimeFormatter weekFormatter = new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendValue(IsoFields.WEEK_BASED_YEAR, 4, 10, SignStyle.EXCEEDS_PAD)
				.appendLiteral("-W")
				.appendValue(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 2)
				.toFormatter(YonaServer.EN_US_LOCALE)

		String currentWeek = weekFormatter.format(YonaServer.now)
		assert responseWeekOverviews.responseData._embedded."yona:weekActivityOverviews"[0].date == currentWeek || isSunday
	}

	private void replyToMessage(messageToReply, User senderUser, messageToSend, boolean isWeek, responseGetActivityDetails) {
		def responseReplyFromBob = appService.postMessageActionWithPassword(messageToReply._links."yona:reply".href, ["message" : messageToSend], senderUser.password)
		assert responseReplyFromBob.status == 200
		assert responseReplyFromBob.responseData.properties["status"] == "done"
		assert responseReplyFromBob.responseData._embedded?."yona:affectedMessages"?.size() == 1
		def replyMessage = responseReplyFromBob.responseData._embedded."yona:affectedMessages"[0]
		assertCommentMessageDetails(replyMessage, senderUser, isWeek, senderUser, responseGetActivityDetails.responseData._links.self.href, messageToSend, messageToReply)
	}

	private getActivityDetailMessages(responseGetActivityDetails, User user, int expectedNumMessages) {
		int defaultPageSize = 4
		int expectedNumMessagesInPage = Math.min(expectedNumMessages, defaultPageSize)
		def response = appService.yonaServer.getResourceWithPassword(responseGetActivityDetails.responseData._links."yona:messages".href, user.password)

		assert response.status == 200
		assert response.responseData?._embedded?."yona:messages"?.size() == expectedNumMessagesInPage
		assert response.responseData.page.size == defaultPageSize
		assert response.responseData.page.totalElements == expectedNumMessages
		assert response.responseData.page.totalPages == Math.ceil(expectedNumMessages / defaultPageSize)
		assert response.responseData.page.number == 0

		assert response.responseData._links?.prev?.href == null
		if (expectedNumMessages > defaultPageSize)
		{
			assert response.responseData._links?.next?.href
		}

		return response
	}

	private void assertNextPage(responseGetActivityDetails, User user) {
		int defaultPageSize = 4
		def response = appService.yonaServer.getResourceWithPassword(responseGetActivityDetails.responseData._links.next.href, user.password)

		assert response.status == 200
		assert response.responseData?._embedded?."yona:messages"?.size() == 1
		assert response.responseData.page.size == defaultPageSize
		assert response.responseData.page.totalElements == 5
		assert response.responseData.page.totalPages == 2
		assert response.responseData.page.number == 1

		assert response.responseData._links?.prev?.href
		assert response.responseData._links?.next?.href == null
	}

	private void assertCommentMessageDetails(message, User user, boolean isWeek, sender, expectedDetailsUrl, expectedText, repliedMessage = null) {
		assert message."@type" == "ActivityCommentMessage"
		assert message.message == expectedText
		assert message.nickname == sender.nickname

		assert message._links?.self?.href?.startsWith(user.messagesUrl)
		assert message._links?.edit?.href == message._links.self.href

		if (repliedMessage) {
			assert repliedMessage._links.self.href == message._links?."yona:repliedMessage"?.href
		}

		if (isWeek) {
			assert message._links?."yona:weekDetails"?.href == expectedDetailsUrl
			assert message._links?."yona:dayDetails"?.href == null
		} else {
			assert message._links?."yona:weekDetails"?.href == null
			assert message._links?."yona:dayDetails"?.href == expectedDetailsUrl
		}
		if (sender instanceof Buddy) {
			assert message._links?."yona:buddy"?.href == sender.url
			assert message._links?."yona:reply"?.href.startsWith(user.url)
		} else {
			assert message._links?."yona:user"?.href == sender.url
			assert message._links?."yona:reply"?.href == null
		}
	}

	private void assertWeekDetailPrevNextLinks(User user, weekActivityForGoal, expectedPrevWeekForGoal, expectedNextWeekForGoal)
	{
		def weekDetails = getWeekDetailsForWeek(user, weekActivityForGoal)
		assert weekDetails?.responseData?._links?."prev"?.href == expectedPrevWeekForGoal?._links?."yona:weekDetails"?.href
		assert weekDetails?.responseData?._links?."next"?.href == expectedNextWeekForGoal?._links?."yona:weekDetails"?.href
	}

	private void assertDayDetailPrevNextLinks(User user, dayActivityForGoal, expectedPrevDayForGoal, expectedNextDayForGoal)
	{
		def dayDetails = getDayDetailsForDay(user, dayActivityForGoal)
		assert dayDetails?.responseData?._links?."prev"?.href == expectedPrevDayForGoal?._links?."yona:dayDetails"?.href
		assert dayDetails?.responseData?._links?."next"?.href == expectedNextDayForGoal?._links?."yona:dayDetails"?.href
	}

	private getDayDetails(responseDayOverviewsAll, User user, Goal goal, int weeksBack, String shortDay) {
		def dayOffset = YonaServer.relativeDateStringToDaysOffset(weeksBack, shortDay)
		def dayActivityOverview = responseDayOverviewsAll.responseData._embedded."yona:dayActivityOverviews"[dayOffset]
		def dayActivityForGoal = dayActivityOverview.dayActivities.find{ it._links."yona:goal".href == goal.url}
		return getDayDetailsForDay(user, dayActivityForGoal)
	}

	private getDayDetailsForDay(User user, dayActivityForGoal) {
		assert dayActivityForGoal?._links?."yona:dayDetails"?.href
		def dayActivityDetailUrl =  dayActivityForGoal?._links?."yona:dayDetails"?.href
		def response = appService.getResourceWithPassword(dayActivityDetailUrl, user.password)
		assert response.status == 200
		return response
	}

	private getWeekDetails(responseWeekOverviewsAll, User user, Goal goal, int weeksBack) {
		def weekActivityOverview = responseWeekOverviewsAll.responseData._embedded."yona:weekActivityOverviews"[weeksBack]
		def weekActivityForGoal = weekActivityOverview.weekActivities.find{ it._links."yona:goal".href == goal.url}
		return getWeekDetailsForWeek(user, weekActivityForGoal)
	}

	private getWeekDetailsForWeek(User user, weekActivityForGoal) {
		assert weekActivityForGoal?._links?."yona:weekDetails"?.href
		def weekActivityDetailUrl =  weekActivityForGoal?._links?."yona:weekDetails"?.href
		def response = appService.getResourceWithPassword(weekActivityDetailUrl, user.password)
		assert response.status == 200
		return response
	}
}
