/* 
 * This file is part of Transitime.org
 * 
 * Transitime.org is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL) as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * Transitime.org is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transitime.org .  If not, see <http://www.gnu.org/licenses/>.
 */
package org.transitime.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transitime.applications.Core;
import org.transitime.config.DoubleConfigValue;
import org.transitime.configData.AvlConfig;
import org.transitime.configData.CoreConfig;
import org.transitime.core.dataCache.PredictionDataCache;
import org.transitime.core.dataCache.VehicleDataCache;
import org.transitime.core.dataCache.VehicleStateManager;
import org.transitime.db.structs.AvlReport;
import org.transitime.db.structs.Block;
import org.transitime.db.structs.Route;
import org.transitime.db.structs.Stop;
import org.transitime.db.structs.Trip;
import org.transitime.db.structs.VehicleEvent;
import org.transitime.utils.Time;

/**
 * This is a very important high-level class. It takes the AVL data and
 * processes it. Matches vehicles to their assignments. Once a match is made
 * then MatchProcessor class is used to generate predictions, arrival/departure
 * times, headway, etc.
 * 
 * @author SkiBu Smith
 * 
 */
public class AvlProcessor {

	// Singleton class
	private static AvlProcessor singleton = new AvlProcessor();
	
	/*********** Configurable Parameters for this module ***********/
	
	private static double getTerminalDistanceForRouteMatching() {
		return terminalDistanceForRouteMatching.getValue();
	}
	private static DoubleConfigValue terminalDistanceForRouteMatching =
			new DoubleConfigValue("transitime.core.terminalDistanceForRouteMatching", 
					100.0);
	
	/************************** Logging *******************************/
	
	private static final Logger logger = 
			LoggerFactory.getLogger(AvlProcessor.class);

	/********************** Member Functions **************************/

	/*
	 * Singleton class so shouldn't use constructor so declared private
	 */
	private AvlProcessor() {		
	}
	
	/**
	 * Returns the singleton AvlProcessor
	 * @return
	 */
	public static AvlProcessor getInstance() {
		return singleton;
	}
	
	/**
	 * Removes predictions and the match for the vehicle and marks
	 * it as unpredictable. Updates VehicleDataCache.
	 * 
	 * @param vehicleId
	 *            The vehicle to be made unpredictable
	 */
	public void makeVehicleUnpredictable(String vehicleId) {
		logger.info("Making vehicleId={} unpredictable", vehicleId);
		
		VehicleState vehicleState =
				VehicleStateManager.getInstance().getVehicleState(vehicleId);

		// Update the state of the vehicle
		vehicleState.setMatch(null);

		// Remove the predictions that were generated by the vehicle
		PredictionDataCache.getInstance().removePredictions(vehicleState);
		
		// Update VehicleDataCache with the new state for the vehicle
		VehicleDataCache.getInstance().updateVehicle(vehicleState);
	}
	
	/**
	 * Removes predictions and the match for the vehicle and marks
	 * is as unpredictable. Also removes block assignment from the
	 * vehicleState. To be used for situations such as assignment
	 * ended or vehicle was reassigned.
	 * 
	 * @param vehicleState
	 *            The vehicle to be made unpredictable
	 */
	public void makeVehicleUnpredictableAndTerminateAssignment(
			VehicleState vehicleState) {
		makeVehicleUnpredictable(vehicleState.getVehicleId());
		
		vehicleState.unsetBlock(BlockAssignmentMethod.ASSIGNMENT_TERMINATED);
	}

	/**
	 * Marks the vehicle as not being predictable and that the assignment has
	 * been grabbed. Updates VehicleDataCache.
	 * 
	 * @param vehicleState
	 */
	public void makeVehicleUnpredictableAndGrabAssignment(
			VehicleState vehicleState) {
		makeVehicleUnpredictable(vehicleState.getVehicleId());
		
		vehicleState.unsetBlock(BlockAssignmentMethod.ASSIGNMENT_GRABBED);
	}
	
	/**
	 * For vehicles that were already predictable but then got a new AvlReport.
	 * Determines where in the block assignment the vehicle now matches to.
	 * Starts at the previous match and then looks ahead from there to find
	 * good spatial matches. Then determines which spatial match is best by
	 * looking at temporal match. Updates the vehicleState with the resulting
	 * best temporal match.
	 * 
	 * @param vehicleState the previous vehicle state
	 * @return the new match, if successful. Otherwise null.
	 */
	public TemporalMatch matchNewFixForPredictableVehicle(
			VehicleState vehicleState) {
		// Make sure state is coherent
		if (!vehicleState.isPredictable() || 
				vehicleState.getMatch() == null) {
			throw new RuntimeException("Called AvlProcessor.matchNewFix() " +
					"for a vehicle that was not already predictable. " + 
					vehicleState);
		}
		
		logger.debug("Matching already predictable vehicle using new AVL " +
				"report. The old spatial match is {}", 
				vehicleState);
		
		// Find possible spatial matches
		List<SpatialMatch> spatialMatches = 
				SpatialMatcher.getSpatialMatches(vehicleState);
		logger.debug("For vehicleId={} found the following {} spatial " +
				"matches: {}",
				vehicleState.getVehicleId(), spatialMatches.size(), 
				spatialMatches);

		// Find best temporal match of the spatial matches
		TemporalMatch bestTemporalMatch =
				TemporalMatcher.getInstance().getBestTemporalMatch(vehicleState, 
						spatialMatches);
		
		// Log this as info since matching is a significant milestone
		logger.info("For vehicleId={} the best match is {}",
				vehicleState.getVehicleId(), bestTemporalMatch);

		// If didn't get a match then remember such in VehicleState
		if (bestTemporalMatch == null)
			vehicleState.incrementNumberOfBadMatches();
		
		// Record this match unless the match was null and haven't
		// reached number of bad matches.
		if (bestTemporalMatch != null || vehicleState.overLimitOfBadMatches()) {
			// If going to make vehicle unpredictable due to bad matches log
			// that info.
			if (bestTemporalMatch == null 
					&& vehicleState.overLimitOfBadMatches()) {
				logger.warn("For vehicleId={} got {} bad matches which is " +
						"over the allowable limit. Therefore setting vehicle " +
						"state to null which will make it unpredictable.",
						vehicleState.getVehicleId(), 
						vehicleState.numberOfBadMatches());
				
				// Log that vehicle is being made unpredictable as a VehicleEvent
				String eventDescription = "Vehicle had " 
						+ vehicleState.numberOfBadMatches() 
						+ " bad spatial matches in a row."
						+ " and so was made unpredictable.";
				VehicleEvent.create(vehicleState.getAvlReport(), 
						vehicleState.getMatch(),
						VehicleEvent.NO_MATCH,
						eventDescription,
						false, // predictable,
						true,  // becameUnpredictable
						null); // supervisor
				
				// Remove block assignment from vehicle
				vehicleState.unsetBlock(BlockAssignmentMethod.COULD_NOT_MATCH);
			}
			
			// Set the match of the vehicle. If null then it will make the 
			// vehicle unpredictable.
			vehicleState.setMatch(bestTemporalMatch);
		} else {
			logger.info("For vehicleId={} got a bad match, {} in a row, so " +
					"not updating match for vehicle",
					vehicleState.getVehicleId(), 
					vehicleState.numberOfBadMatches());
		}
		
		// Return results
		return bestTemporalMatch;
	}

	/**
	 * When matching a vehicle to a route we are currently assuming that we
	 * cannot make predictions or match a vehicle to a specific trip until after
	 * vehicle has started on its trip. This is because there will be multiple
	 * trips per route and we cannot tell which one the vehicle is on time wise
	 * until the vehicle has started the trip.
	 * 
	 * @param match
	 * @return True if the match can be used when matching vehicle to a route
	 */
	private static boolean matchOkForRouteMatching(SpatialMatch match) {
		return match.awayFromTerminals(getTerminalDistanceForRouteMatching());
	}
	
	/**
	 * Attempts to match vehicle to the specified route by finding appropriate
	 * block assignment. Updates the VehicleState with the new block assignment
	 * and match. These will be null if vehicle could not successfully be
	 * matched to block.
	 * 
	 * @param routeId
	 * @param vehicleState
	 * @return True if successfully matched vehicle to block assignment for
	 *         specified route
	 */
	private boolean matchVehicleToRouteAssignment(String routeId, 
			VehicleState vehicleState) {
		// Make sure params are good
		if (routeId == null) {
			logger.error("matchVehicleToRouteAssignment() called with null " +
					"routeId. {}", vehicleState);
		}
		
		logger.debug("Matching unassigned vehicle to routeId={}. {}", 
				routeId, vehicleState);

		// Convenience variables
		AvlReport avlReport = vehicleState.getAvlReport();

		// Determine which blocks are currently active for the route.
		// Multiple services can be active on a given day. Therefore need
		// to look at all the active ones to find out what blocks are active...
		List<Block> allBlocksForRoute = new ArrayList<Block>();
		ServiceUtils serviceUtils = Core.getInstance().getServiceUtils();
		List<String> serviceIds = 
				serviceUtils.getServiceIds(avlReport.getDate());
		for (String serviceId : serviceIds) {
			List<Block> blocksForService = Core.getInstance().getDbConfig().
					getBlocksForRoute(serviceId, routeId);
			if (blocksForService != null) {
				allBlocksForRoute.addAll(blocksForService);
			}
		}

		List<SpatialMatch> allPotentialSpatialMatchesForRoute = 
				new ArrayList<SpatialMatch>();
		
		// Go through each block and determine best spatial matches
		for (Block block : allBlocksForRoute) {
			// If the block isn't active at this time then ignore it. This way 
			// don't look at each trip to see if it is active which is important
			// because looking at each trip means all the trip data including
			// travel times needs to be lazy loaded, which can be slow.
			if (!block.isActive(avlReport.getDate())) {
				if (logger.isDebugEnabled()) {
					logger.debug("For vehicleId={} ignoring block ID {} with " +
							"start_time={} and end_time={} because not " +
							"active for time {}",
							avlReport.getVehicleId(), block.getId(),
							Time.timeOfDayStr(block.getStartTime()),
							Time.timeOfDayStr(block.getEndTime()),
							Time.timeStr(avlReport.getDate()));
				}
				continue;
			}
			
			// Determine which trips for the block are active. If none then
			// continue to the next block
			List<Trip> potentialTrips = 
					block.getTripsCurrentlyActive(avlReport);			
			if (potentialTrips.isEmpty()) 
				continue;
			
			logger.debug("For vehicleId={} examining potential trips for " +
					"match to block ID {}. {}",
					avlReport.getVehicleId(), block.getId(), 
					potentialTrips);
			
			// Get the potential spatial matches
			List<SpatialMatch> spatialMatchesForBlock = 
					SpatialMatcher.getSpatialMatches(vehicleState, 
							potentialTrips,	block);

			// Add appropriate spatial matches to list
			for (SpatialMatch spatialMatch : spatialMatchesForBlock) {
				if (!SpatialMatcher.problemMatchDueToLackOfHeadingInfo(
						spatialMatch, vehicleState) 
						&& matchOkForRouteMatching(spatialMatch))
					allPotentialSpatialMatchesForRoute.add(spatialMatch);
			}
		} // End of going through each block to determine spatial matches
		
		// For the spatial matches get the best temporal match
		TemporalMatch bestMatch = TemporalMatcher.getInstance()
				.getBestTemporalMatchComparedToSchedule(avlReport,
						allPotentialSpatialMatchesForRoute);
		logger.debug("For vehicleId={} best temporal match is {}", 
				avlReport.getVehicleId(), bestMatch);

		// If got a valid match then keep track of state
		BlockAssignmentMethod blockAssignmentMethod = null;
		boolean predictable = false;
		Block block = null;
		if (bestMatch != null) {
			blockAssignmentMethod = BlockAssignmentMethod.AVL_FEED_ROUTE_ASSIGNMENT;
			predictable = true;
			block = bestMatch.getBlock();
			logger.info("vehicleId={} matched to routeId={}. " +
					"Vehicle is now predictable. Match={}",
					avlReport.getVehicleId(), routeId, bestMatch);

			// Record a corresponding VehicleEvent
			String eventDescription = "Vehicle successfully matched to route " +
					"assignment and is now predictable.";
			VehicleEvent.create(avlReport, bestMatch,
					VehicleEvent.PREDICTABLE,
					eventDescription,
					true,  // predictable
					false, // becameUnpredictable
					null); // supervisor
		} else {
			logger.debug("For vehicleId={} could not assign to routeId={}. " +
					"Therefore vehicle is not being made predictable.",
					avlReport.getVehicleId(), routeId);
		}

		// Update the vehicle state with the determined block assignment
		// and match. Of course might not have been successful in 
		// matching vehicle, but still should update VehicleState.
		vehicleState.setMatch(bestMatch);
		vehicleState.setBlock(block, blockAssignmentMethod,
				avlReport.getAssignmentId(), predictable);

		return predictable;
	}
	
	/**
	 * Attempts to match the vehicle to the new block assignment. Updates
	 * the VehicleState with the new block assignment and match. These will be
	 * null if vehicle could not successfully be matched to block.
	 * 
	 * @param block
	 * @param vehicleState
	 * @return True if successfully matched vehicle to block assignment
	 */
	private boolean matchVehicleToBlockAssignment(Block block, 
			VehicleState vehicleState) {
		// Make sure params are good
		if (block == null) {
			logger.error("matchVehicleToBlockAssignment() called with null " +
					"block. {}", vehicleState);
		}
		
		logger.debug("Matching unassigned vehicle to block assignment {}. {}", 
				block.getId(), vehicleState);

		// Convenience variables
		AvlReport avlReport = vehicleState.getAvlReport();
		BlockAssignmentMethod blockAssignmentMethod = null;
		boolean predictable = false;

		// Determine best spatial matches for trips that are currently
		// active. Currently active means that the AVL time is within
		// reasonable range of the start time and within the end time of 
		// the trip.
		List<Trip> potentialTrips = block.getTripsCurrentlyActive(avlReport);
		List<SpatialMatch> spatialMatches = 
				SpatialMatcher.getSpatialMatches(vehicleState, potentialTrips, 
						block);
		logger.debug("For vehicleId={} and blockId={} spatial matches={}",
				avlReport.getVehicleId(), block.getId(), spatialMatches);

		// Determine the best temporal match
		TemporalMatch bestMatch = TemporalMatcher.getInstance()
				.getBestTemporalMatchComparedToSchedule(avlReport,
						spatialMatches);
		logger.debug("Best temporal match for vehicleId={} is {}",
				avlReport.getVehicleId(), bestMatch);
		
		// If best match is a non-layover but cannot confirm that the heading
		// is acceptable then don't consider this a match. Instead, wait till
		// get another AVL report at a different location so can see if making 
		// progress along route in proper direction.
		if (SpatialMatcher.problemMatchDueToLackOfHeadingInfo(bestMatch, 
				vehicleState)) {
			logger.debug("Found match but could not confirm that heading is "
					+ "proper. Therefore not matching vehicle to block. {}", 
					bestMatch);
			return false;
		}
		
		// If couldn't find an adequate spatial/temporal match then resort
		// to matching to a layover stop at a terminal. 
		if (bestMatch == null) {
			logger.debug("For vehicleId={} could not find reasonable " +
					"match so will try to match to layover stop.",
					avlReport.getVehicleId());
			
			Trip trip = TemporalMatcher.getInstance().
					matchToLayoverStopEvenIfOffRoute(avlReport, potentialTrips);
			if (trip != null) {
				SpatialMatch beginningOfTrip = 
						new SpatialMatch(vehicleState.getVehicleId(),
						block, 
						block.getTripIndex(trip.getId()),
						0,    //  stopPathIndex 
						0,    // segmentIndex 
						0.0,  // distanceToSegment
						0.0); // distanceAlongSegment

				bestMatch = new TemporalMatch(beginningOfTrip, 
						new TemporalDifference(0));
				logger.debug("For vehicleId={} could not find reasonable " +
						"match for blockId={} so had to match to layover. " +
						"The match is {}",
						avlReport.getVehicleId(), block.getId(), bestMatch);
			} else {
				logger.debug("For vehicleId={} couldn't find match for " +
						"blockId={}", 
						avlReport.getVehicleId(), block.getId());
			}
		}
		
		// If got a valid match then keep track of state
		if (bestMatch != null) {
			blockAssignmentMethod = BlockAssignmentMethod.AVL_FEED_BLOCK_ASSIGNMENT;
			predictable = true;
			logger.info("For vehicleId={} matched to blockId={}. " +
					"Vehicle is now predictable. Match={}",
					avlReport.getVehicleId(), block.getId(), bestMatch);

			// Record a corresponding VehicleEvent
			String eventDescription = "Vehicle successfully matched to block " +
					"assignment and is now predictable.";
			VehicleEvent.create(avlReport, bestMatch,
					VehicleEvent.PREDICTABLE,
					eventDescription,
					true,  // predictable
					false, // becameUnpredictable
					null); // supervisor
			
			// Make sure no other vehicle is using that assignment
			// if it is supposed to be exclusive. This needs to be done before
			// the VehicleDataCache is updated with info from the current
			// vehicle since this will affect all vehicles assigned to the
			// block.
			unassignOtherVehiclesFromBlock(block, avlReport.getVehicleId());
		} else {
			logger.info("For vehicleId={} could not assign to blockId={}. " +
					"Therefore vehicle is not being made predictable.",
					avlReport.getVehicleId(), block.getId());
		}

		// Update the vehicle state with the determined block assignment
		// and match. Of course might not have been successful in 
		// matching vehicle, but still should update VehicleState.
		vehicleState.setMatch(bestMatch);
		vehicleState.setBlock(block, blockAssignmentMethod,
				avlReport.getAssignmentId(), predictable);

		// Return whether successfully matched the vehicle
		return predictable;
	}
	
	/**
	 * If the block assignment is supposed to be exclusive then looks for any
	 * vehicles assigned to the specified block and removes the assignment from
	 * them. This of course needs to be called before a vehicle is assigned to a
	 * block since *ALL* vehicles assigned to the block will have their
	 * assignment removed.
	 * 
	 * @param block
	 * @param newVehicleId
	 *            for logging message
	 */
	private void unassignOtherVehiclesFromBlock(Block block, 
			String newVehicleId) {
		// Determine vehicles assigned to block
		Collection<String> vehiclesAssignedToBlock = 
				VehicleDataCache.getInstance().getVehiclesByBlockId(block.getId());
		
		// For each vehicle assigned to the block unassign it
		VehicleStateManager stateManager = VehicleStateManager.getInstance();
		for (String vehicleId : vehiclesAssignedToBlock) {
			VehicleState vehicleState =
					stateManager.getVehicleState(vehicleId);
			if (block.shouldBeExclusive() || vehicleState.isForSchedBasedPreds()) {
				logger.info("Assigning vehicleId={} to blockId={} but "
						+ "vehicleId={} already assigned to that block so "
						+ "removing assignment from that vehicle.",
						newVehicleId, block.getId(), vehicleId);
				makeVehicleUnpredictableAndGrabAssignment(vehicleState);
			}
		}
	}
	
	/**
	 * To be called when vehicle doesn't already have a block assignment or the
	 * vehicle is being reassigned. Uses block assignment from the AvlReport to
	 * try to match the vehicle to the assignment. If successful then the
	 * vehicle can be made predictable. The AvlReport is obtained from the
	 * vehicleState parameter.
	 * 
	 * @param avlReport
	 * @param vehicleState
	 *            provides current AvlReport plus is updated by this method with
	 *            the new state.
	 * @return true if successfully assigned vehicle
	 */
	public boolean matchVehicleToAssignment(VehicleState vehicleState) {
		logger.debug("Matching unassigned vehicle to assignment. {}", 
				vehicleState);

		// Initialize some variables
		AvlReport avlReport = vehicleState.getAvlReport();
		
		// Remove old block assignment if there was one
		if (vehicleState.isPredictable() && 
				vehicleState.hasNewAssignment(avlReport)) {
			logger.info("For vehicleId={} the vehicle assignment is being "
					+ "changed to assignmentId={}", 
					vehicleState.getVehicleId(), vehicleState.getAssignmentId());
			makeVehicleUnpredictableAndTerminateAssignment(vehicleState);					
		}

		// If the vehicle has a block assignment from the AVLFeed
		// then use it.
		Block block = BlockAssigner.getInstance().getBlockAssignment(avlReport);
		if (block != null) {
			// There is a block assignment from AVL feed so use it.
			return matchVehicleToBlockAssignment(block, vehicleState);			
		} else {
			// If there is a route assignment from AVL feed us it
			String routeId = 
					BlockAssigner.getInstance().getRouteIdAssignment(avlReport);
			if (routeId != null) {
				// There is a route assignment so use it
				return matchVehicleToRouteAssignment(routeId, vehicleState);
			}
		}
		
		// There was no valid block or route assignment from AVL feed so can't
		// do anything. But set the block assignment for the vehicle
		// so it is up to date. This call also sets the vehicle state
		// to be unpredictable.
		BlockAssignmentMethod blockAssignmentMethod = null;
		vehicleState.unsetBlock(blockAssignmentMethod);
		return false;
	}
	
	/**
	 * Looks at the last match in vehicleState to determine if at end of block
	 * assignment. Updates vehicleState if at end of block. Note that this will
	 * not always work since might not actually get an AVL report that matches
	 * to the last stop.
	 * 
	 * @param vehicleState
	 * @return True if end of the block was reached with the last match.
	 */
	private boolean handlePossibleEndOfBlock(VehicleState vehicleState) {
		// Determine if at end of block assignment
		TemporalMatch temporalMatch = vehicleState.getMatch();
		if (temporalMatch != null) {
			VehicleAtStopInfo atStopInfo = temporalMatch.getAtStop();
			if (atStopInfo != null) {
				if (atStopInfo.atEndOfBlock()) {
					logger.info("For vehicleId={} the end of the block={} " +
							"was reached so will make vehicle unpredictable", 
							vehicleState.getVehicleId(), 
							temporalMatch.getBlock().getId());
					
					// Log that vehicle is being made unpredictable as a VehicleEvent
					String eventDescription = "Block assignment " 
							+ vehicleState.getBlock().getId() 
							+ " ended for vehicle so it was made unpredictable.";
					VehicleEvent.create(vehicleState.getAvlReport(), 
							vehicleState.getMatch(),
							VehicleEvent.END_OF_BLOCK,
							eventDescription,
							false,  // predictable,
							true,   // becameUnpredictable
							null);  // supervisor 

					
					// At end of block assignment so remove it
					makeVehicleUnpredictableAndTerminateAssignment(vehicleState);
					
					// Return that end of block reached
					return true;
				}
			}
		}
		
		// End of block wasn't reached so return false
		return false;
	}
	
	/**
	 * Determines the real-time schedule adherence for the vehicle. To be called
	 * after the vehicle is matched.
	 * <p>
	 * If schedule adherence is not within bounds then will try to match the
	 * vehicle to the assignment again. This can be important if system is run
	 * for a while and then paused and then started up again. Vehicle might
	 * continue to match to the pre-paused match, but by then the vehicle might
	 * be on a whole different trip, causing schedule adherence to be really far
	 * off. To prevent this the vehicle is re-matched to the assignment.
	 * <p>
	 * Updates vehicleState accordingly.
	 * 
	 * @param vehicleState
	 * @return
	 */
	private TemporalDifference checkScheduleAdherence(VehicleState vehicleState) {
		logger.debug("Processing real-time schedule adherence for vehicleId={}",
				vehicleState.getVehicleId());
	
		// Determine the schedule adherence for the vehicle
		TemporalDifference scheduleAdherence = 
				RealTimeSchedAdhProcessor.generate(vehicleState);
				
		// If vehicle is just sitting at terminal past its scheduled departure
		// time then indicate such as an event.
		if (vehicleState.getMatch().isWaitStop() 
				&& scheduleAdherence != null
				&& scheduleAdherence.isLaterThan(CoreConfig.getAllowableLateAtTerminalForLoggingEvent())
				&& vehicleState.getMatch().getAtStop() != null) {
			// Create description for VehicleEvent
			String stopId = vehicleState.getMatch().getStopPath().getStopId();
			Stop stop = Core.getInstance().getDbConfig().getStop(stopId);
			Route route = vehicleState.getMatch().getRoute();
			VehicleAtStopInfo stopInfo = vehicleState.getMatch().getAtStop();
			Integer scheduledDepartureTime = stopInfo.getScheduleTime().getDepartureTime();	

			String description = "Vehicle " + vehicleState.getVehicleId() 
					+ " still at stop " + stopId
					+ " \"" + stop.getName() + "\" for route \"" + route.getName() 
					+ "\" " + scheduleAdherence.toString() + ". Scheduled departure time was " 
					+ Time.timeOfDayStr(scheduledDepartureTime);
			
			// Create, store in db, and log the VehicleEvent
			VehicleEvent.create(vehicleState.getAvlReport(), vehicleState.getMatch(),
					VehicleEvent.NOT_LEAVING_TERMINAL, 
					description, 
					true,  // predictable
					false, // becameUnpredictable 
					null); // supervisor			

		}
		
		// Make sure the schedule adherence is reasonable
		if (scheduleAdherence != null 
				&& !scheduleAdherence.isWithinBounds(vehicleState)) {
			logger.warn("For vehicleId={} schedule adherence {} is not " +
					"between the allowable bounds. Therefore trying to match " +
					"the vehicle to its assignmet again to see if get better " +
					"temporal match by matching to proper trip.",
					vehicleState.getVehicleId(), scheduleAdherence);
			
			// Log that vehicle is being made unpredictable as a VehicleEvent
			String eventDescription = "Vehicle had schedule adherence of "
					+ scheduleAdherence + " which is beyond acceptable "
					+ "limits. Therefore vehicle made unpredictable.";
			VehicleEvent.create(vehicleState.getAvlReport(), 
					vehicleState.getMatch(),
					VehicleEvent.NO_MATCH,
					eventDescription,
					false, // predictable,
					true, // becameUnpredictable
					null);  // supervisor 
			
			// Clear out match because it is no good! This is especially
			// important for when determining arrivals/departures because
			// that looks at previous match and current match.
			vehicleState.setMatch(null);
			
			// Schedule adherence not reasonable so match vehicle to assignment
			// again.
			matchVehicleToAssignment(vehicleState);
			
			// Now that have matched vehicle to assignment again determine
			// schedule adherence once more.
			scheduleAdherence = 
					RealTimeSchedAdhProcessor.generate(vehicleState);
		}
		
		// Store the schedule adherence with the vehicle
		vehicleState.setRealTimeSchedAdh(scheduleAdherence);
		
		// Return results
		return scheduleAdherence;
	}
	
	/**
	 * Processes the AVL report by matching to the assignment and generating
	 * predictions and such. Sets VehicleState for the vehicle based on the
	 * results. Also stores AVL report into the database (if not in playback
	 * mode).
	 * 
	 * @param avlReport
	 *            The new AVL report to be processed
	 * @param rescursiveCall
	 *            Set to true if this method is calling itself. Used to make
	 *            sure that any bug can't cause infinite recursion.
	 */
	public void lowLevelProcessAvlReport(AvlReport avlReport,
			boolean rescursiveCall) {
		// Determine previous state of vehicle
		String vehicleId = avlReport.getVehicleId();
		VehicleState vehicleState =
				VehicleStateManager.getInstance().getVehicleState(vehicleId);
		
		// Since modifying the VehicleState should synchronize in case another
		// thread simultaneously processes data for the same vehicle. This  
		// would be extremely rare but need to be safe.
		synchronized (vehicleState) {
			// Keep track of last AvlReport even if vehicle not predictable. 
			vehicleState.setAvlReport(avlReport);			

			// Do the matching depending on the old and the new assignment
			// for the vehicle.
			boolean matchAlreadyPredictableVehicle = vehicleState.isPredictable()  
					&& !vehicleState.hasNewAssignment(avlReport);
			boolean matchToNewAssignment = avlReport.hasValidAssignment() 
					&& (!vehicleState.isPredictable() 
							|| vehicleState.hasNewAssignment(avlReport))
					&& !vehicleState.previousAssignmentProblematic(avlReport);
			
			if (matchAlreadyPredictableVehicle) {
				// Vehicle was already assigned and assignment hasn't
				// changed so update the match of where the vehicle is 
				// within the assignment.
				matchNewFixForPredictableVehicle(vehicleState);								
			} else if (matchToNewAssignment) {
				// New assignment so match the vehicle to it
				matchVehicleToAssignment(vehicleState);
			} else {
				// Can't do anything so set the match to null, which also
				// specifies that the vehicle is not predictable. In the 
				// future might want to change code to try to auto assign 
				// vehicle.
				vehicleState.setMatch(null);
			}

			// If the last match is actually valid then generate associated
			// data like predictions and arrival/departure times.
			if (vehicleState.isPredictable() 
					&& vehicleState.lastMatchIsValid()) {
				// Determine and store the schedule adherence. If schedule 
				// adherence is bad then try matching vehicle to assignment
				// again. This can make vehicle unpredictable if can't match
				// vehicle to assignment.
				checkScheduleAdherence(vehicleState);
				
				// Only continue processing if vehicle is still predictable 
				// since calling checkScheduleAdherence() can make it
				// unpredictable if schedule adherence is really bad.
				if (vehicleState.isPredictable()) {
					// Generates the corresponding data for the vehicle such as 
					// predictions and arrival times
					MatchProcessor.getInstance().
						generateResultsOfMatch(vehicleState);
					
					// If finished block assignment then should remove 
					// assignment
					boolean endOfBlockReached = 
							handlePossibleEndOfBlock(vehicleState);
					
					// If just reached the end of the block and took the block 
					// assignment away and made the vehicle unpredictable then
					// should see if the AVL report could be used to assign 
					// vehicle to the next assignment. This is needed for 
					// agencies like Zhengzhou which is frequency based and 
					// where each block assignment is only a single trip and
					// when vehicle finishes one trip/block it can go into the 
					// next block right away.
					if (endOfBlockReached) {
						if (rescursiveCall) {
							// This method was already called recursively which 
							// means unassigned vehicle at end of block but then
							// it got assigned to end of block again. This
							// indicates a bug since vehicles at end of block
							// shouldn't be reassigned to the end of the block
							// again. Therefore log problem and don't try to
							// assign vehicle again.
							logger.error(
									"AvlProcessor.lowLevelProcessAvlReport() " +
									"called recursively, which is wrong. {}", 
									vehicleState);
						} else {
							// Actually process AVL report again to see if can 
							// assign to new assignment.
							lowLevelProcessAvlReport(avlReport, true);
						}					
					} // End of if end of block reached
				}
			}
			
			// Now that VehicleState has been updated need to update the
			// VehicleDataCache so that when data queried for API the proper
			// info is provided.
			VehicleDataCache.getInstance().updateVehicle(vehicleState);
		}  // End of synchronizing on vehicleState	}
	}
	
	/**
	 * First does housekeeping for the AvlReport (stores it in db, logs it,
	 * etc). Processes the AVL report by matching to the assignment and
	 * generating predictions and such. Sets VehicleState for the vehicle based
	 * on the results. Also stores AVL report into the database (if not in
	 * playback mode).
	 * 
	 * @param avlReport
	 *            The new AVL report to be processed
	 */
	public void processAvlReport(AvlReport avlReport) {
		// The beginning of processing AVL data is an important milestone 
		// in processing data so log it as info.
		logger.info("====================================================" +
				"AvlProcessor processing {}", avlReport);		
		
		// Record when the AvlReport was actually processed. This is done here so
		// that the value will be set when the avlReport is stored in the database
		// using the DbLogger.
		avlReport.setTimeProcessed();

		// Store the AVL report into the database
		if (!CoreConfig.onlyNeedArrivalDepartures() 
				&& !avlReport.isForSchedBasedPreds())
			Core.getInstance().getDbLogger().add(avlReport);
		
		// If any vehicles have timed out then handle them. This is done
		// here instead of using a regular timer so that it will work
		// even when in playback mode or when reading batch data.
		TimeoutHandler.getInstance().handlePossibleTimeout(avlReport);
		
		// Logging to syserr just for debugging.
		if (AvlConfig.shouldLogToStdOut()) {
			System.err.println("Processing avlReport for vehicleId=" + 
					avlReport.getVehicleId() + 
					//" AVL time=" + Time.timeStrMsec(avlReport.getTime()) +
					" " + avlReport +
					" ...");
		}
		
		// Do the low level work of matching vehicle and then generating results
		lowLevelProcessAvlReport(avlReport, false);
	}
	
}
