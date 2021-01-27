package au.blindmot.remittance.factory;

import org.adempiere.base.IProcessFactory;
import org.compiere.process.ProcessCall;
import org.compiere.util.CLogger;
import au.blindmot.remittance.Remittance;

public class RemittanceFactory implements IProcessFactory {
	private CLogger log = CLogger.getCLogger(RemittanceFactory.class);

	@Override
	public ProcessCall newProcessInstance(String className) {
		if(className.equals("au.blindmot.remittance.Remittance"))
		{
			log.warning("----------In au.blindmot.remittance.factory.RemittanceFactory");
			return new Remittance();
		}
		return null;
	}

}
