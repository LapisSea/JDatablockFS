package com.lapissea.cfs.tools.logging;

public interface DataLogger{
	
	class Closed extends IllegalStateException{
		public Closed(){}
		public Closed(String message){
			super(message);
		}
	}
	
	interface Session{
		class Blank implements Session{
			public static final Session INSTANCE=new Blank();
			private Blank(){}
			
			@Override
			public void log(MemFrame frame){}
			
			@Override
			public void finish(){}
			
			@Override
			public void reset(){}
			@Override
			public void delete(){}
			@Override
			public String getName(){
				return "";
			}
		}
		
		void log(MemFrame frame);
		
		void finish();
		
		void reset();
		
		void delete();
		
		String getName();
	}
	
	class Blank implements DataLogger{
		public static final DataLogger INSTANCE=new Blank();
		private Blank(){}
		
		@Override
		public Session getSession(String name){
			return Session.Blank.INSTANCE;
		}
		
		@Override
		public void destroy(){}
		@Override
		public boolean isActive(){
			return false;
		}
	}
	
	Session getSession(String name);
	
	void destroy();
	
	boolean isActive();
}
