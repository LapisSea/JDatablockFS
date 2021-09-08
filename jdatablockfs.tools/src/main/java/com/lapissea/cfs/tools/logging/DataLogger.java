package com.lapissea.cfs.tools.logging;

public interface DataLogger{
	
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
		}
		
		void log(MemFrame frame);
		
		void finish();
		
		void reset();
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
	}
	
	Session getSession(String name);
	
	void destroy();
}
