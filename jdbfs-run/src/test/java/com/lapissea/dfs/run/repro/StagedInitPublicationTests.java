package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.type.StagedInit;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

public class StagedInitPublicationTests{
	@DataProvider
	public Object[][] publicationFields(){
		return new Object[][]{{"state"}, {"initInfo"}};
	}

	// These fields are read by lock-free completion checks, so publication must be explicit.
	@Test(dataProvider = "publicationFields")
	public void completionFieldsAreVolatile(String name) throws NoSuchFieldException{
		var field = StagedInit.class.getDeclaredField(name);
		assertThat(Modifier.isVolatile(field.getModifiers())).as("volatile publication of %s", name).isTrue();
	}
}

