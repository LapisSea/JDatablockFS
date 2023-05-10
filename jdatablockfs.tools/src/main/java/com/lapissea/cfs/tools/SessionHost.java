package com.lapissea.cfs.tools;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.util.UtilL;
import com.lapissea.util.event.change.ChangeRegistry;
import com.lapissea.util.event.change.ChangeRegistryInt;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;

public class SessionHost implements DataLogger{
	
	public static final class ParsedFrame{
		int                    index;
		WeakReference<Cluster> cluster = new WeakReference<>(null);
		Throwable              displayError;
		Chunk                  lastHoverChunk;
		public ParsedFrame(int index){
			this.index = index;
		}
		
		public Optional<Cluster> getCluster(){
			return Optional.ofNullable(cluster.get());
		}
	}
	
	public record CachedFrame(MemFrame memData, ParsedFrame parsed){ }
	
	public class HostedSession implements Session{
		public final List<CachedFrame> frames   = new ArrayList<>();
		public final ChangeRegistryInt framePos = new ChangeRegistryInt(-1);
		
		private boolean markForDeletion;
		
		private final String name;
		
		private HostedSession(String name){
			this.name = name;
			framePos.register(activeFrame::set);
		}
		
		@Override
		public String getName(){
			return name;
		}
		
		@Override
		public void log(MemFrame frame){
			if(frames.size()>10000){
				synchronized(frames){
					var               ints      = new Random().ints(0, frames.size()).distinct().limit(1000).toArray();
					List<CachedFrame> tmpFrames = new LinkedList<>();
					IntStream.range(0, (int)(frames.size()*0.7)).filter(i -> !UtilL.contains(ints, i)).mapToObj(frames::get).forEach(tmpFrames::add);
					frames.clear();
					MemFrame lastFull;
					{
						var f = tmpFrames.remove(0);
						lastFull = new MemFrame(f.memData.frameId(), f.memData.timeDelta(), f.memData.bytes(), f.memData.ids(), f.memData.e());
						frames.add(new CachedFrame(lastFull, f.parsed));
					}
					while(!tmpFrames.isEmpty()){
						var fr   = tmpFrames.remove(0);
						var diff = MemFrame.diff(lastFull, fr.memData);
						frames.add(new CachedFrame(diff == null? fr.memData : diff, fr.parsed));
						if(diff == null){
							lastFull = new MemFrame(fr.memData.frameId(), fr.memData.timeDelta(), fr.memData.bytes(), fr.memData.ids(), fr.memData.e());
						}
					}
					System.gc();
				}
			}
			
			synchronized(frames){
				frames.add(new CachedFrame(frame, new ParsedFrame(frames.isEmpty()? 0 : frames.get(frames.size() - 1).parsed.index + 1)));
			}
			synchronized(framePos){
				framePos.set(-1);
			}
			
			if(blockLogTillDisplay){
				UtilL.sleepWhile(() -> framePos.get() != frames.size() - 1);
			}
		}
		
		@Override
		public void finish(){ }
		
		@Override
		public void reset(){
			frames.clear();
			setFrame(0);
		}
		
		@Override
		public void delete(){
			reset();
			markForDeletion = true;
			sessionMarkedForDeletion = true;
		}
		
		public void setFrame(int frame){
			if(frame>frames.size() - 1) frame = frames.size() - 1;
			framePos.set(frame);
		}
	}
	
	private boolean sessionMarkedForDeletion = false;
	private boolean destroyed                = false;
	
	private final Map<String, HostedSession> sessions = new LinkedHashMap<>();
	
	public final  ChangeRegistry<Optional<HostedSession>> activeSession = new ChangeRegistry<>(Optional.empty());
	public final  ChangeRegistryInt                       activeFrame   = new ChangeRegistryInt(-1);
	private final boolean                                 blockLogTillDisplay;
	
	
	private long lastSessionSet;
	
	public SessionHost(boolean blockLogTillDisplay){ this.blockLogTillDisplay = blockLogTillDisplay; }
	
	@Override
	public Session getSession(String name){
		if(destroyed) throw new IllegalStateException();
		
		HostedSession ses;
		synchronized(sessions){
			ses = sessions.computeIfAbsent(name, HostedSession::new);
		}
		var tim = System.nanoTime();
		if(tim - lastSessionSet>4000_000_000L && activeSession.get().orElse(null) != ses){
			lastSessionSet = tim;
			setActiveSession(ses);
		}
		
		return ses;
	}
	private void setActiveSession(HostedSession session){
		this.activeSession.set(Optional.of(session));
	}
	
	public void cleanUpSessions(){
		if(!sessionMarkedForDeletion) return;
		sessionMarkedForDeletion = false;
		
		synchronized(sessions){
			sessions.values().removeIf(s -> s.markForDeletion);
			activeSession.get().filter(s -> s.markForDeletion).flatMap(s -> sessions.values().stream().findAny()).ifPresent(this::setActiveSession);
		}
	}
	
	@Override
	public void destroy(){
		destroyed = true;
		synchronized(sessions){
			sessions.values().forEach(Session::finish);
			sessions.clear();
		}
		activeSession.set(Optional.empty());
	}
	@Override
	public boolean isActive(){
		return !destroyed;
	}
	
	public void prevSession(){
		
		HostedSession ses;
		synchronized(sessions){
			if(sessions.size()<=1) return;
			
			find:
			{
				HostedSession last = null;
				for(var value : sessions.values()){
					ses = last;
					last = value;
					var as = activeSession.get();
					if(as.isPresent() && value == as.get()){
						if(ses == null){
							for(var session : sessions.values()){
								last = session;
							}
							ses = last;
						}
						break find;
					}
				}
				ses = sessions.values().iterator().next();
			}
		}
		setActiveSession(ses);
		
	}
	public void nextSession(){
		HostedSession ses;
		synchronized(sessions){
			if(sessions.size()<=1) return;
			
			boolean found = false;
			find:
			{
				for(var value : sessions.values()){
					if(found){
						ses = value;
						break find;
					}
					var as = activeSession.get();
					found = as.isPresent() && value == as.get();
				}
				ses = sessions.values().iterator().next();
			}
		}
		setActiveSession(ses);
	}
}
