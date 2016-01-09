package nu.yona.server.analysis.service;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import nu.yona.server.analysis.entities.Activity;
import nu.yona.server.analysis.entities.GoalConflictMessage;
import nu.yona.server.goals.entities.ActivityCategory;
import nu.yona.server.goals.entities.Goal;
import nu.yona.server.goals.service.ActivityCategoryDTO;
import nu.yona.server.goals.service.ActivityCategoryService;
import nu.yona.server.messaging.service.MessageDestinationDTO;
import nu.yona.server.messaging.service.MessageService;
import nu.yona.server.properties.AnalysisServiceProperties;
import nu.yona.server.properties.YonaProperties;
import nu.yona.server.subscriptions.service.UserAnonymizedDTO;
import nu.yona.server.subscriptions.service.UserAnonymizedService;

@RunWith(MockitoJUnitRunner.class)
public class AnalysisEngineServiceTests
{
	private Map<String, Goal> goalMap = new HashMap<String, Goal>();

	@Mock
	private ActivityCategoryService mockActivityCategoryService;
	@Mock
	private UserAnonymizedService mockUserAnonymizedService;
	@Mock
	private MessageService mockMessageService;
	@Mock
	private YonaProperties mockYonaProperties;
	@Mock
	private AnalysisEngineCacheService mockAnalysisEngineCacheService;
	@InjectMocks
	private AnalysisEngineService service = new AnalysisEngineService();

	private Goal gamblingGoal;
	private Goal newsGoal;
	private Goal gamingGoal;
	private MessageDestinationDTO anonMessageDestination;
	private UUID userAnonID;

	@Before
	public void setUp()
	{
		gamblingGoal = Goal.createInstance(ActivityCategory.createInstance("gambling", false,
				new HashSet<String>(Arrays.asList("poker", "lotto")), Collections.emptySet()));
		newsGoal = Goal.createInstance(ActivityCategory.createInstance("news", false,
				new HashSet<String>(Arrays.asList("refdag", "bbc")), Collections.emptySet()));
		gamingGoal = Goal.createInstance(ActivityCategory.createInstance("gaming", false,
				new HashSet<String>(Arrays.asList("games")), Collections.emptySet()));

		goalMap.put("gambling", gamblingGoal);
		goalMap.put("news", newsGoal);
		goalMap.put("gaming", gamingGoal);

		when(mockYonaProperties.getAnalysisService()).thenReturn(new AnalysisServiceProperties());

		when(mockActivityCategoryService.getAllActivityCategoryEntities()).thenReturn(new HashSet<ActivityCategory>(
				goalMap.values().stream().map(goal -> goal.getActivityCategory()).collect(Collectors.toSet())));
		when(mockActivityCategoryService.getAllActivityCategories()).thenReturn(new HashSet<ActivityCategoryDTO>(
				goalMap.values().stream().map(goal -> ActivityCategoryDTO.createInstance(goal.getActivityCategory()))
						.collect(Collectors.toSet())));

		// Set up UserAnonymized instance.
		anonMessageDestination = new MessageDestinationDTO(UUID.randomUUID());
		Set<Goal> goals = new HashSet<Goal>(Arrays.asList(gamblingGoal, gamingGoal));
		UserAnonymizedDTO userAnon = new UserAnonymizedDTO(goals, anonMessageDestination, Collections.emptySet());
		userAnonID = UUID.randomUUID();

		// Stub the UserAnonymizedRepository to return our user.
		when(mockUserAnonymizedService.getUserAnonymized(userAnonID)).thenReturn(userAnon);
	}

	/*
	 * Tests the method to get all relevant categories.
	 */
	@Test
	public void getRelevantSmoothwallCategories()
	{
		assertEquals(new HashSet<String>(Arrays.asList("poker", "lotto", "refdag", "bbc", "games")),
				service.getRelevantSmoothwallCategories());
	}

	/*
	 * Tests that two conflict messages are generated when the conflict interval is passed.
	 */
	@Test
	public void conflictInterval()
	{
		// Normally there is one conflict message sent.
		// Set a short conflict interval such that the conflict messages are not aggregated.
		AnalysisServiceProperties p = new AnalysisServiceProperties();
		p.setUpdateSkipWindow(1L);
		p.setConflictInterval(10L);
		when(mockYonaProperties.getAnalysisService()).thenReturn(p);

		Activity earlierActivity = Activity.createInstance(userAnonID, gamblingGoal);
		earlierActivity.setEndTime(new Date());
		when(mockAnalysisEngineCacheService.fetchLatestActivityForUser(eq(userAnonID), eq(gamblingGoal.getID()), any()))
				.thenReturn(earlierActivity);

		// Execute the analysis engine service after a period of inactivity longer than the conflict interval.

		try
		{
			Thread.sleep(11L);
		}
		catch (InterruptedException e)
		{

		}

		Set<String> conflictCategories = new HashSet<String>(Arrays.asList("lotto"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategories, "http://localhost/test1"));

		// Verify that there is a new conflict message sent.
		verify(mockMessageService, times(1)).sendMessage(any(), eq(anonMessageDestination));
		// Verify that the existing conflict message was not updated in the cache.
		verify(mockAnalysisEngineCacheService, never()).updateLatestActivityForUser(earlierActivity);

		// Restore default properties.
		when(mockYonaProperties.getAnalysisService()).thenReturn(new AnalysisServiceProperties());
	}

	/**
	 * Tests that a conflict message is created when analysis service is called with a matching category.
	 */
	@Test
	public void messageCreatedOnMatch()
	{
		// Execute the analysis engine service.
		Set<String> conflictCategories = new HashSet<String>(Arrays.asList("lotto"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategories, "http://localhost/test"));

		// Verify that there is a new conflict message sent.
		ArgumentCaptor<GoalConflictMessage> message = ArgumentCaptor.forClass(GoalConflictMessage.class);
		verify(mockMessageService).sendMessage(message.capture(), eq(anonMessageDestination));
		assertEquals(userAnonID, message.getValue().getRelatedUserAnonymizedID());
		assertEquals(gamblingGoal.getID(), message.getValue().getActivity().getGoalID());
	}

	/**
	 * Tests that a conflict message is created when analysis service is called with a not matching and a matching category.
	 */
	@Test
	public void messageCreatedOnMatchOneCategoryOfMultiple()
	{
		// Execute the analysis engine service.
		Set<String> conflictCategories = new HashSet<String>(Arrays.asList("refdag", "lotto"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategories, "http://localhost/test"));

		// Verify that there is a new conflict message sent.
		ArgumentCaptor<GoalConflictMessage> message = ArgumentCaptor.forClass(GoalConflictMessage.class);
		verify(mockMessageService).sendMessage(message.capture(), eq(anonMessageDestination));
		assertEquals(gamblingGoal.getID(), message.getValue().getActivity().getGoalID());
	}

	/**
	 * Tests that multiple conflict messages are created when analysis service is called with multiple matching categories.
	 */
	@Test
	public void messagesCreatedOnMatchMultiple()
	{
		// Execute the analysis engine service.
		Set<String> conflictCategories = new HashSet<String>(Arrays.asList("lotto", "games"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategories, "http://localhost/test"));

		// Verify that there are 2 conflict messages sent, for both goals.
		ArgumentCaptor<GoalConflictMessage> message = ArgumentCaptor.forClass(GoalConflictMessage.class);
		verify(mockMessageService, times(2)).sendMessage(message.capture(), eq(anonMessageDestination));
		assertEquals(new HashSet<UUID>(Arrays.asList(gamblingGoal.getID(), gamingGoal.getID())),
				message.getAllValues().stream().map(m -> m.getActivity().getGoalID()).collect(Collectors.toSet()));
	}

	/**
	 * Tests that a conflict message is updated when analysis service is called with a matching category after a short time.
	 */
	@Test
	public void messageAggregation()
	{
		// Normally there is one conflict message sent.
		// Set update skip window to 0 such that the conflict messages are aggregated immediately.
		AnalysisServiceProperties p = new AnalysisServiceProperties();
		p.setUpdateSkipWindow(0L);
		when(mockYonaProperties.getAnalysisService()).thenReturn(p);

		Activity earlierActivity = Activity.createInstance(userAnonID, gamblingGoal);
		earlierActivity.setEndTime(new Date());
		when(mockAnalysisEngineCacheService.fetchLatestActivityForUser(eq(userAnonID), eq(gamblingGoal.getID()), any()))
				.thenReturn(earlierActivity);

		// Execute the analysis engine service.
		Set<String> conflictCategories1 = new HashSet<String>(Arrays.asList("lotto"));
		Set<String> conflictCategories2 = new HashSet<String>(Arrays.asList("poker"));
		Set<String> conflictCategoriesNotMatching1 = new HashSet<String>(Arrays.asList("refdag"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategoriesNotMatching1, "http://localhost/test"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategories1, "http://localhost/test1"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategories2, "http://localhost/test2"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategoriesNotMatching1, "http://localhost/test3"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategories2, "http://localhost/test4"));

		// Verify that there is no new conflict message sent.
		verify(mockMessageService, never()).sendMessage(any(), eq(anonMessageDestination));
		// Verify that the existing conflict message was updated in the cache.
		verify(mockAnalysisEngineCacheService, times(3)).updateLatestActivityForUser(earlierActivity);

		// Restore default properties.
		when(mockYonaProperties.getAnalysisService()).thenReturn(new AnalysisServiceProperties());
	}

	/**
	 * Tests that no conflict messages are created when analysis service is called with non-matching category.
	 */
	@Test
	public void noMessagesCreatedOnNoMatch()
	{
		// Execute the analysis engine service.
		Set<String> conflictCategories = new HashSet<String>(Arrays.asList("refdag"));
		service.analyze(new PotentialConflictDTO(userAnonID, conflictCategories, "http://localhost/test"));

		// Verify that there is no conflict message sent.
		verify(mockMessageService, never()).sendMessage(any(), eq(anonMessageDestination));
	}
}