package com.lapissea.cfs.logging;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public class EventEnvironment extends IOInstance<EventEnvironment>{
	
	private static final long NO_ID=-1;
	
	public static class EventReference extends IOInstance<EventReference>{
		
		private long  refId=NO_ID;
		private Event eventObj;
		
		@IOValue
		private String name;
		
		
		public EventReference(){}
		public EventReference(Event event, String name){
			this.eventObj=Objects.requireNonNull(event);
			this.name=Objects.requireNonNull(name);
		}
		
		@IOValue
		private void setRefId(long refId){
			eventObj=null;
			this.refId=refId;
		}
		
		@IOValue
		private long getRefId(){
			if(refId==NO_ID){
				if(eventObj==null) throw new IllegalStateException("Reference has no id");
				var id=eventObj.id;
				if(id==NO_ID) throw new IllegalStateException("Event was not logged");
				refId=id;
			}
			return refId;
		}
		
		public Event getEvent(EventEnvironment env){
			if(eventObj==null){
				eventObj=env.eventById(refId);
			}
			return eventObj;
		}
	}
	
	public static class Event extends IOInstance<Event>{
		
		@IOValue
		private long id;
		
		@IOValue
		private long   timelinePos;
		@IOValue
		@IONullability(IONullability.Mode.NULLABLE)
		private String timelineName;
		@IOValue
		private String name;
		
		@IOValue
		private List<EventReference> references;
		
	}
	
	
	@IOValue
	private long idCounter;
	
	@IOValue
	private       List<Event>           events =new ArrayList<>();
	private final List<Supplier<Event>> pending=new ArrayList<>();
	
	public <T> void log(Function<T, Event> addapter, T value){
		synchronized(pending){
			pending.add(()->addapter.apply(value));
		}
	}
	
	private void log(Event event){
		Objects.requireNonNull(event);
		
		synchronized(event){
			if(event.id!=NO_ID){
				throw new IllegalArgumentException("Event has already been logged");
			}
			event.id=newId();
		}
		events.add(event);
	}
	
	private synchronized long newId(){
		return idCounter++;
	}
	
	private Event eventById(long id){
		if(id==NO_ID) throw new IllegalArgumentException();
		return events.stream().filter(e->e.id==id).findAny().orElseGet(()->{
			if(pending.isEmpty())throw new NoSuchElementException();
			flushPending();
			return eventById(id);
		});
	}
	
	public void emit(OutputStream dest){
		flushPending();
	}
	
	
	private void flushPending(){
		synchronized(pending){
			pending.stream().map(Supplier::get).forEach(this::log);
			pending.clear();
		}
	}
	
}
