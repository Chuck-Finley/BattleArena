package mc.alk.arena.controllers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import mc.alk.arena.BattleArena;
import mc.alk.arena.competition.match.Match;
import mc.alk.arena.competition.util.TeamJoinHandler;
import mc.alk.arena.competition.util.TeamJoinHandler.TeamJoinResult;
import mc.alk.arena.events.matches.MatchFinishedEvent;
import mc.alk.arena.listeners.ArenaListener;
import mc.alk.arena.objects.ArenaPlayer;
import mc.alk.arena.objects.MatchParams;
import mc.alk.arena.objects.MatchState;
import mc.alk.arena.objects.arenas.Arena;
import mc.alk.arena.objects.arenas.ArenaInterface;
import mc.alk.arena.objects.arenas.ArenaType;
import mc.alk.arena.objects.events.MatchEventHandler;
import mc.alk.arena.objects.options.JoinOptions;
import mc.alk.arena.objects.options.TransitionOption;
import mc.alk.arena.objects.pairs.ParamTeamPair;
import mc.alk.arena.objects.pairs.QPosTeamPair;
import mc.alk.arena.objects.queues.ArenaMatchQueue;
import mc.alk.arena.objects.queues.QueueObject;
import mc.alk.arena.objects.queues.TeamQObject;
import mc.alk.arena.objects.teams.CompositeTeam;
import mc.alk.arena.objects.teams.Team;
import mc.alk.arena.objects.teams.TeamHandler;
import mc.alk.arena.objects.tournament.Matchup;

import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerTeleportEvent;


public class BattleArenaController implements Runnable, TeamHandler, ArenaListener{

	boolean stop = false;

	private final ArenaMatchQueue amq = new ArenaMatchQueue();
	final private Set<Match> running_matches = Collections.synchronizedSet(new CopyOnWriteArraySet<Match>());
	final private Map<ArenaType,Set<Match>> unfilled_matches = Collections.synchronizedMap(new ConcurrentHashMap<ArenaType,Set<Match>>());
	private Map<String, Arena> allarenas = new ConcurrentHashMap<String, Arena>();
	long lastTimeCheck = 0;
	public BattleArenaController(){
		MethodController.addMethods(this.getClass(), this.getClass().getMethods());
	}
	/// Run is Thread Safe as well as every method and object it uses
	public void run() {
		Match match = null;
		while (!stop){
			match = amq.getArenaMatch();
			if (match != null){
				openMatch(match);
				startMatch(match);
			}
		}
	}

	public void openMatch(Match match){
		match.addArenaListener(this);
//		match.addTransitionListener(this);
		synchronized(running_matches){
			running_matches.add(match);
		}
		/// BattleArena controller only tracks the players while they are in the queue
		/// now that a match is starting the players are no longer our responsibility
		unhandle(match);
		match.open();
		TeamJoinHandler tjh = match.getTeamJoinHandler();
		if (tjh != null && match.hasWaitroom() && !tjh.isFull()){
			Set<Match> matches = unfilled_matches.get(match.getParams().getType());
			if (matches == null){
				matches = Collections.synchronizedSet(new HashSet<Match>());
				unfilled_matches.put(match.getParams().getType(), matches);
			}
			matches.add(match);
		}
	}

	private void unhandle(Match match) {
		Collection<Team> teams = match.getOriginalTeams();
		if (teams == null)
			teams = match.getTeams();
		if (teams == null)
			return;
		for (Team team : teams){
			unhandle(team);
		}
	}
	private void unhandle(final Team team) {
		TeamController.removeTeamHandler(team, this);
		for (ArenaPlayer ap: team.getPlayers()){
			MethodController.updateAllEventListeners(this, MatchState.ONFINISH, ap);}
		if (team instanceof CompositeTeam){
			for (Team t: ((CompositeTeam)team).getOldTeams()){
				TeamController.removeTeamHandler(t, this);
			}
		}
	}

	public void startMatch(Match arenaMatch) {
		/// arenaMatch run calls.... broadcastMessage ( which unfortunately is not thread safe)
		/// So we have to schedule a sync task... again
		unhandle(arenaMatch); /// Since teams and players can join between onOpen and onStart.. re unhandle
		Bukkit.getScheduler().scheduleSyncDelayedTask(BattleArena.getSelf(), arenaMatch);
	}

	@MatchEventHandler
	public void matchFinished(MatchFinishedEvent event){
		//		if (Defaults.DEBUG ) System.out.println("BattleArenaController::matchComplete=" + am + ":" );
		Match am = event.getMatch();
		removeMatch(am); /// handles removing running match from the BArenaController

		unhandle(am);/// unhandle any teams that were added during the match
		Arena arena = allarenas.get(am.getArena().getName());
		if (arena != null)
			amq.add(arena); /// add it back into the queue
	}

	public void updateArena(Arena arena) {
		allarenas.put(arena.getName(), arena);
		if (amq.removeArena(arena) != null){ /// if its not being used
			amq.add(arena);
		}
	}

	public void addArena(Arena arena) {
		allarenas.put(arena.getName(), arena);
		amq.add(arena);
	}

	public Map<String, Arena> getArenas(){return allarenas;}
	public QPosTeamPair addToQue(TeamQObject tqo) {
		Team team = tqo.getTeam();
		if (joinExistingMatch(tqo)){
			return null;}
		QPosTeamPair qpp = amq.add(tqo);
		if (qpp != null && qpp.pos >=0){
			TeamController.addTeamHandler(team,this);
			/// If the same world flag is set, lets not let them change worlds while waiting in the queue
			if (tqo.getMatchParams().getTransitionOptions().hasOptionAt(MatchState.PREREQS, TransitionOption.SAMEWORLD)){
				MethodController.updateAllEventListeners(this, MatchState.PREREQS, team.getPlayers());}
		}
		return qpp;
	}

	@MatchEventHandler(begin=MatchState.PREREQS, end=MatchState.ONFINISH)
	public void onPlayerChangeWorld(PlayerTeleportEvent event){
		if (event.isCancelled())
			return;
		if (event.getFrom().getWorld().getUID() != event.getTo().getWorld().getUID()){
			ArenaPlayer ap = BattleArena.toArenaPlayer(event.getPlayer());
			ParamTeamPair ptp = amq.removeFromQue(ap);
			if (ptp != null){
				unhandle(ptp.team);
				ptp.team.sendMessage("&cYou have been removed from the queue for changing worlds");
			}
		}
	}
	private boolean joinExistingMatch(TeamQObject tqo) {
		if (unfilled_matches.isEmpty()){
			return false;}
		MatchParams params = tqo.getMatchParams();
		synchronized(unfilled_matches){
			Set<Match> matches = unfilled_matches.get(params.getType());
			if (matches == null)
				return false;
			Iterator<Match> iter = matches.iterator();
			while (iter.hasNext()){
				Match match = iter.next();
				/// We dont want people joining in a non waitroom state
				if (!match.canStillJoin()){
					iter.remove();
					continue;
				}
				if (match.getParams().matches(params)){
					TeamJoinHandler tjh = match.getTeamJoinHandler();
					if (tjh == null)
						continue;
					boolean result = false;
					TeamJoinResult tjr = tjh.joiningTeam(tqo);
					switch(tjr.status){
					case ADDED_TO_EXISTING: case ADDED: result = true;
					default: break;
					}
					/// if we are now full, remove from unfilled
					if (tjh.isFull()){
						iter.remove();
						match.start();
					}
					return result;
				}
			}
		}
		return false;
	}

	public boolean isInQue(ArenaPlayer p) {return amq.isInQue(p);}
	public QPosTeamPair getCurrentQuePos(ArenaPlayer p) {return amq.getQueuePos(p);}

	/**
	 * Remove the player from the queue
	 * @param player
	 * @return The ParamTeamPair object if the player was found.  Otherwise returns null
	 */
	public ParamTeamPair removeFromQue(ArenaPlayer p) {
		Team t = TeamController.getTeam(p);
		if (t == null)
			return null;
		TeamController.removeTeamHandler(t, this);
		return amq.removeFromQue(p);
	}
	public ParamTeamPair removeFromQue(Team t) {
		TeamController.removeTeamHandler(t, this);
		return amq.removeFromQue(t);
	}
	public void addMatchup(Matchup m) {amq.addMatchup(m);}
	public Arena reserveArena(Arena arena) {return amq.reserveArena(arena);}
	public Arena getArena(String arenaName) {return allarenas.get(arenaName);}

	public Arena removeArena(Arena arena) {
		Arena a = amq.removeArena(arena);
		if (a != null){
			allarenas.remove(arena.getName());}
		return a;
	}

	public void deleteArena(Arena arena) {
		removeArena(arena);
		ArenaInterface ai = new ArenaInterface(arena);
		ai.delete();
	}

	public void arenaChanged(Arena arena){
		try{
			if (removeArena(arena) != null){
				addArena(arena);}
		} catch (Exception e){
			e.printStackTrace();
		}
	}

	public Arena nextArenaByMatchParams(MatchParams mp){
		return amq.getNextArena(mp);
	}

	public Arena getArenaByMatchParams(MatchParams mp, JoinOptions jp) {
		for (Arena a : allarenas.values()){
			if (a.valid() && a.matches(mp,jp)){
				return a;}
		}
		return null;
	}

	public List<Arena> getArenas(MatchParams mp) {
		List<Arena> arenas = new ArrayList<Arena>();
		for (Arena a : allarenas.values()){
			if (a.valid() && a.matches(mp,null)){
				arenas.add(a);}
		}
		return arenas;
	}

	public Arena getArenaByNearbyMatchParams(MatchParams mp, JoinOptions jp) {
		Arena possible = null;
		int sizeDif = Integer.MAX_VALUE;
		int m1 = mp.getMinTeamSize();
		for (Arena a : allarenas.values()){
			if (a.valid() && a.matches(mp,jp)){
				return a;}
			if (a.getArenaType() != mp.getType())
				continue;
			int m2 = a.getParameters().getMinTeamSize();
			if (m2 > m1 && m2 -m1 < sizeDif){
				sizeDif = m2 - m1;
				possible = a;
			}
		}
		return possible;
	}


	public Map<Arena,List<String>> getNotMachingArenaReasons(MatchParams mp, JoinOptions jp) {
		Map<Arena,List<String>> reasons = new HashMap<Arena, List<String>>();
		for (Arena a : allarenas.values()){
			if (a.getArenaType() != mp.getType()){
				continue;
			}
			if (jp != null && !jp.matches(a))
				continue;
			if (!a.valid()){
				reasons.put(a, a.getInvalidReasons());}
			if (!a.matches(mp,jp)){
				List<String> rs = reasons.get(a);
				if (rs == null){
					reasons.put(a, a.getInvalidMatchReasons(mp, jp));
				} else {
					rs.addAll(a.getInvalidMatchReasons(mp, jp));
				}
			}
		}
		return reasons;
	}

	/**
	 * We dont care if they leave queues
	 */
	@Override
	public boolean canLeave(ArenaPlayer p) {
		return true;
	}
	public boolean hasArenaSize(int i) {return getArenaBySize(i) != null;}
	public Arena getArenaBySize(int i) {
		for (Arena a : allarenas.values()){
			if (a.getParameters().matchesTeamSize(i)){
				return a;}
		}
		return null;
	}

	private void removeMatch(Match am){
		synchronized(running_matches){
			running_matches.remove(am);
		}
		synchronized(unfilled_matches){
			Set<Match> matches = unfilled_matches.get(am.getParams().getType());
			if (matches != null){
				matches.remove(am);
			}
		}
	}

	public synchronized void stop() {
		if (stop)
			return;
		stop = true;
		amq.stop();
		amq.purgeQueue();
		synchronized(running_matches){
			for (Match am: running_matches){
				cancelMatch(am);
				Arena a = am.getArena();
				if (a != null){
					Arena arena = allarenas.get(a.getName());
					if (arena == null)
						amq.add(arena);
				}
			}
			running_matches.clear();
		}
	}

	/**
	 * If they are in a queue, take them out
	 */
	public boolean leave(ArenaPlayer p) {
		ParamTeamPair ptp = removeFromQue(p);
		if (ptp != null){
			ptp.team.sendMessage("&cYour team has left the queue b/c &6"+p.getDisplayName()+"c has left");
		}
		/// else they are in a match, but those will be dealt with by the match itself
		/// In all cases, we no longer have to worry about this player or their team
		return true;
	}

	public boolean cancelMatch(Arena arena) {
		synchronized(running_matches){
			for (Match am: running_matches){
				if (am.getArena().getName().equals(arena.getName())){
					cancelMatch(am);
					return true;
				}
			}
		}
		return false;
	}

	public boolean cancelMatch(ArenaPlayer p) {
		Match am = getMatch(p);
		if (am==null)
			return false;
		cancelMatch(am);
		return true;
	}

	public boolean cancelMatch(Team team) {
		Set<ArenaPlayer> ps = team.getPlayers();
		for (ArenaPlayer p : ps){
			return cancelMatch(p);
		}
		return false;
	}

	public void cancelMatch(Match am){
		am.cancelMatch();
		for (Team t: am.getTeams()){
			t.sendMessage("&4!!!&e This arena match has been cancelled");
		}
	}

	public Match getArenaMatch(Arena a) {
		synchronized(running_matches){
			for (Match am: running_matches){
				if (am.getArena().getName().equals(a.getName())){
					return am;
				}
			}
		}
		return null;
	}

	public Match getMatch(ArenaPlayer p) {
		synchronized(running_matches){
			for (Match am: running_matches){
				if (am.hasPlayer(p)){
					return am;
				}
			}
		}
		return null;
	}

	public Match getMatch(Arena arena) {
		synchronized(running_matches){
			for (Match am: running_matches){
				if (am.getArena().equals(arena)){
					return am;
				}
			}
		}
		return null;
	}

	@Override
	public String toString(){
		return "[BAC]";
	}

	public String toDetailedString(){
		StringBuilder sb = new StringBuilder();
		sb.append(amq);
		sb.append("------ arenas -------\n");
		for (Arena a : allarenas.values()){
			sb.append(a +"\n");
		}
		sb.append("------ running  matches -------\n");
		synchronized(running_matches){
			for (Match am : running_matches){
				sb.append(am + "\n");
			}
		}

		return sb.toString();
	}


	public void removeAllArenas() {
		synchronized(running_matches){
			for (Match am: running_matches){
				am.cancelMatch();
			}
		}

		amq.stop();
		amq.removeAllArenas();
		synchronized(allarenas){
			allarenas.clear();
		}
		amq.resume();

	}
	public void removeAllArenas(ArenaType arenaType) {
		synchronized(running_matches){
			for (Match am: running_matches){
				if (am.getArena().getArenaType() == arenaType)
					am.cancelMatch();
			}
		}

		amq.stop();
		amq.removeAllArenas(arenaType);
		synchronized(allarenas){
			for (String aName: allarenas.keySet()){
				Arena a = allarenas.get(aName);
				if (a.getArenaType() == arenaType)
					allarenas.remove(aName);
			}
		}
		amq.resume();
	}

	public void cancelAllArenas() {
		amq.stop();
		amq.clearTeamQueues();
		synchronized(running_matches){
			for (Match am: running_matches){
				am.cancelMatch();
			}
		}
		amq.resume();
	}

	public void resume() {
		amq.resume();
	}

	public Collection<Team> purgeQueue() {
		Collection<Team> teams = amq.purgeQueue();
		TeamController.removeTeams(teams, this);
		return teams;
	}

	public boolean hasRunningMatches() {
		return !running_matches.isEmpty();
	}
	public QueueObject getQueueObject(ArenaPlayer player) {
		return amq.getQueueObject(player);
	}
	public List<String> getInvalidQueueReasons(QueueObject qo) {
		return amq.invalidReason(qo);
	}
}
