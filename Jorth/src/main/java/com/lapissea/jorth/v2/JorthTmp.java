package com.lapissea.jorth.v2;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.LogUtil;

public class JorthTmp{
	
	public static void main(String[] args) throws MalformedJorthException{
		LogUtil.println("Starting");
		Thread.ofVirtual().start(()->{});
		long t=System.currentTimeMillis();
		
		var jorth =new Jorth(null);
		var writer=jorth.writer();
		
		writer.addImport(String.class);
		
		writer.write(
			"""
				visibility public
				class FooBar start
					visibility public
					field foo typ.String
					
					visibility public
					function getFoo
						returns typ.String
					start
						get this foo
					end
				end
				""");
		LogUtil.println();
		LogUtil.println(System.currentTimeMillis()-t);
	}
}
