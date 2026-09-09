package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.CodeBlock;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

public class ReproJorthSimIaeTests{

	private static CodeBlock body() throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("test.InvalidOperand"));
		return cd.function("run").staticAcc().body();
	}

	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot increment .* by int")
	void addIntRejectsReference() throws MalformedJorth{
		body().val("text").add(1);
	}

	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot increment .* by double")
	void addDoubleRejectsFloat() throws MalformedJorth{
		body().val(1.5F).add(1.5);
	}

	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot bit shift .*")
	void leftShiftRejectsReference() throws MalformedJorth{
		body().val("text").bitShiftLeft(1);
	}

	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot bit shift .*")
	void rightShiftRejectsFloat() throws MalformedJorth{
		body().val(1.5F).bitShiftRight(false, 1);
	}

	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "Cannot bit shift .*")
	void unsignedRightShiftRejectsReference() throws MalformedJorth{
		body().val("text").bitShiftRight(true, 1);
	}
}
