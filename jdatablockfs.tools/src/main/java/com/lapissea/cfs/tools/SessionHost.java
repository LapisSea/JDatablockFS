package com.lapissea.cfs.tools;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.MemFrame;
import com.lapissea.util.event.change.ChangeRegistry;
import com.lapissea.util.event.change.ChangeRegistryInt;

import java.lang.ref.WeakReference;
import java.util.*;

public class SessionHost implements DataLogger{
	
	public static final class ParsedFrame{
		final int index;
		WeakReference<Cluster> cluster=new WeakReference<>(null);
		Throwable              displayError;
		Chunk                  lastHoverChunk;
		public ParsedFrame(int index){
			this.index=index;
		}
		
		public Optional<Cluster> getCluster(){
			return Optional.ofNullable(cluster.get());
		}
	}
	
	public static record CachedFrame(MemFrame memData, ParsedFrame parsed){}
	
	public class HostedSession implements Session{
		public final List<CachedFrame> frames  =new ArrayList<>();
		public final ChangeRegistryInt framePos=new ChangeRegistryInt(-1);
		
		private boolean markForDeletion;
		
		private final String name;
		
		private HostedSession(String name){
			this.name=name;
			framePos.register(activeFrame::set);
		}
		
		@Override
		public String getName(){
			return name;
		}
		
		@Override
		public synchronized void log(MemFrame frame){
			frames.add(new CachedFrame(frame, new ParsedFrame(frames.size())));
			synchronized(framePos){
				framePos.set(-1);
			}
		}
		
		@Override
		public void finish(){}
		
		@Override
		public void reset(){
			frames.clear();
			setFrame(0);
		}
		
		@Override
		public void delete(){
			reset();
			markForDeletion=true;
			sessionMarkedForDeletion=true;
		}
		
		public void setFrame(int frame){
			if(frame>frames.size()-1) frame=frames.size()-1;
			framePos.set(frame);
		}
	}
	
	private boolean sessionMarkedForDeletion=false;
	private boolean destroyed               =false;
	
	private final Map<String, HostedSession> sessions=new LinkedHashMap<>();
	
	public final ChangeRegistry<Optional<HostedSession>> activeSession=new ChangeRegistry<>(Optional.empty());
	public final ChangeRegistryInt                       activeFrame  =new ChangeRegistryInt();
	
	
	@Override
	public Session getSession(String name){
		if(destroyed) throw new IllegalStateException();
		
		var ses=sessions.computeIfAbsent(name, HostedSession::new);
		setActiveSession(ses);
		return ses;
	}
	private void setActiveSession(HostedSession session){
		this.activeSession.set(Optional.of(session));
	}
	
	public void cleanUpSessions(){
		if(!sessionMarkedForDeletion) return;
		sessionMarkedForDeletion=false;
		
		sessions.values().removeIf(s->s.markForDeletion);
		activeSession.get().filter(s->s.markForDeletion).flatMap(s->sessions.values().stream().findAny()).ifPresent(this::setActiveSession);
	}
	
	@Override
	public void destroy(){
		destroyed=true;
		sessions.values().forEach(Session::finish);
		activeSession.set(Optional.empty());
		sessions.clear();
	}
	
	public void prevSession(){
		if(sessions.size()<=1) return;
		
		HostedSession ses;
		find:
		{
			HostedSession last=null;
			for(var value : sessions.values()){
				ses=last;
				last=value;
				var as=activeSession.get();
				if(as.isPresent()&&value==as.get()){
					if(ses==null){
						for(var session : sessions.values()){
							last=session;
						}
						ses=last;
					}
					break find;
				}
			}
			ses=sessions.values().iterator().next();
		}
		setActiveSession(ses);
		
	}
	public void nextSession(){
		if(sessions.size()<=1) return;
		
		boolean       found=false;
		HostedSession ses;
		find:
		{
			for(var value : sessions.values()){
				if(found){
					ses=value;
					break find;
				}
				var as=activeSession.get();
				found=as.isPresent()&&value==as.get();
			}
			ses=sessions.values().iterator().next();
		}
		setActiveSession(ses);
	}
}
