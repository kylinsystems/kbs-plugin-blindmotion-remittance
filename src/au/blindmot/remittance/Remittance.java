package au.blindmot.remittance;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.logging.Level;

import javax.sql.RowSet;

import org.compiere.model.MBPartner;
import org.compiere.model.MClient;
import org.compiere.model.MMailText;
import org.compiere.model.MPaySelection;
import org.compiere.model.MUser;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;
import org.compiere.util.EMail;
import org.compiere.util.Env;

public class Remittance extends SvrProcess {
	private int c_PaySelection_ID = 0;
	private StringBuilder mailText = new StringBuilder();
	private StringBuilder mailSubject = new StringBuilder();
	private StringBuilder mailResult = new StringBuilder();
	private static String REMITTANCE_HEADER = "Our ref,Your ref1,Your ref2,Amount";
	private static String DEFAULT_MAILBODY = "We have today requested our bank to pay the following:";
	private static String DEFAULT_SUBJECT = "Remittance from ";
	private String subject = null;
	private StringBuilder  mailBody = new StringBuilder();
	private StringBuilder mailHeader = new StringBuilder();
	private MClient client = null;
	private int mMailTextID = 0;
	private int c_PaySelectionLine_ID = 0;

	@Override
	protected void prepare() {

		ProcessInfoParameter[] paras = getParameter();
		for(ProcessInfoParameter para : paras)
		{
			String paraName = para.getParameterName();
			if(paraName.equalsIgnoreCase("C_PaySelection_ID"))
			{
				c_PaySelection_ID = para.getParameterAsInt();
			}
				
			else if(paraName.equalsIgnoreCase("C_PaySelectionLine_ID"))
			{
				c_PaySelectionLine_ID  = para.getParameterAsInt();
			}
			else
				log.log(Level.SEVERE, "Unknown Parameter: " + paraName);
		}
		
	}

	@Override
	protected String doIt() throws Exception {
		/*
		 }*TODO Auto-generated method stub
		 *SELECT * FROM c_payselectionline cp WHERE cp.c_payselection_id = '1000009' ORDER BY cp.c_payselectioncheck_id
		 *Iterate through result set, build a string of pay advice for each invoice on the same cp.c_payselectioncheck_id
		 *Send email as each cp.c_payselectioncheck_id is completed.
		 *Use a record attached to BP called 'remittance'; if doesn't exist send to first contact with an email address.
		 *Use a mail template called 'Remittacnce', if doesn't exist just send as is.
		 *Check that email is sent; if not, resend (how many times?)
		 *Alert user as to success or failure - message.
		 *
		 */
		
		//Check if there's a mailtext called 'remittance', grab ID if there is.
		StringBuilder sql = new StringBuilder("SELECT r_mailtext_id ");
		sql.append("FROM r_mailtext WHERE ");
		sql.append("name LIKE 'remittance'");
		mMailTextID = DB.getSQLValue(get_TrxName(), sql.toString());
		setHeaderAndText();
		
		client = new MClient(getCtx(), Env.getAD_Client_ID(getCtx()), false, get_TrxName());
		mailSubject.append(subject);
		mailSubject.append(" " + client.getName());
		
		/*
		 * If c_PaySelection_ID != 0//It's a paymentselection - send email to all
		 * If c_PaySelectionLine_ID != 0//It's a payselection line - send email to one line only.
		 * 
		 * TODO: The process gets both parameters if it's called from a Payment Selection Line
		 * This results in sql1 getting set twice, the second time it simply sends email to the first selection line
		 * Fix by checking if the 2 paqrras have values, if they do only send to c_PaySelectionLine_ID, if they don't 
		 * sed to all  with c_PaySelection_ID 
		 */
		StringBuilder sql1 = new StringBuilder();
		
		if(c_PaySelection_ID != 0)//
		{
			sql1 = getSqlPaySelection(c_PaySelection_ID);
		}
		
		if(c_PaySelectionLine_ID != 0)
		{
			sql1 = getSqlPaySelectionLine(c_PaySelectionLine_ID);
		}
		
		//Get result set of checkIDs
		
		
		RowSet rowSet = DB.getRowSet(sql1.toString());
	
		while (rowSet.next()) {  
            int checkID = rowSet.getInt(1);
			String processed = rowSet.getString(2);
			BigDecimal payamt = rowSet.getBigDecimal(3);
			int bpid = prepareEmail(checkID, processed, payamt);
			sendEmail(bpid);
	
		}
		//To here	
		//sendEmail(invoiceGlobal);
		String msg = mailResult.toString();
		addLog(c_PaySelection_ID, null, null, msg, MPaySelection.Table_ID, c_PaySelection_ID);
		return "@OK@";
		
	}
	
	private StringBuilder getSqlPaySelectionLine(int c_PaySelectionLine_ID3) {
		StringBuilder sql = new StringBuilder("SELECT cpsc.c_payselectioncheck_id, cpsc.processed, cpsc.payamt ");
		sql.append("FROM c_payselectioncheck cpsc ");
		sql.append("JOIN c_payselectionline cpl ON cpl.c_payselectioncheck_id = cpsc.c_payselectioncheck_id ");
		sql.append("WHERE cpl.c_payselectionline_id =  ");
		sql.append(c_PaySelectionLine_ID3);
		return sql;
	}

	private StringBuilder getSqlPaySelection(int c_PaySelection_ID2) {
		StringBuilder sql = new StringBuilder("SELECT cpsc.c_payselectioncheck_id, cpsc.processed, cpsc.payamt ");
		sql.append("FROM c_payselectioncheck cpsc ");
		sql.append("JOIN c_payselection cp ON cp.c_payselection_id = cpsc.c_payselection_id ");
		sql.append("WHERE cp.c_payselection_id = ");
		sql.append(c_PaySelection_ID2);
		return sql;
	}

	private int prepareEmail(int checkID, String processed, BigDecimal payamt) {
	
		int bpid = 0;
		mailText.setLength(0);
		mailText.append(mailBody);
		
		StringBuilder sql = new StringBuilder("SELECT inv.documentno AS ourref, inv.poreference AS yourref1, ");
		sql.append("inv.lve_poinvoiceno AS yourref2, cal.amount AS amtpaid, cp.c_bpartner_id AS bpid ");
		sql.append("FROM c_payselectioncheck cpsc ");
		sql.append("JOIN c_payment cp ON cp.c_payment_id = cpsc.c_payment_id ");
		sql.append("JOIN c_allocationline cal ON cal.c_payment_id = cp.c_payment_id ");
		sql.append("JOIN c_invoice inv ON inv.c_invoice_id = cal.c_invoice_id ");
		sql.append("WHERE cpsc.c_payselectioncheck_id = ");
		sql.append(checkID);
		RowSet rowSet = DB.getRowSet(sql.toString());
	
		try {
			while (rowSet.next()) {
					int invoiceID = rowSet.getInt(1);
					String ref1 = rowSet.getString(2);
					String ref2 = rowSet.getString(3);
					BigDecimal invamt = rowSet.getBigDecimal(4);
					//TODO: remove - sign from invamount
					bpid = rowSet.getInt(5);
				
					mailText.append(invoiceID);
					mailText.append("\t");
					if(ref1 != null)
					{
						mailText.append(ref1);
					}
					
					mailText.append("\t");
					if(ref2 != null)
					{
						mailText.append(ref2);
					}
					BigDecimal positiveInvAmt = invamt.abs();
					mailText.append("\t" + "\t");
					mailText.append("$" + positiveInvAmt);
					mailText.append("\n");
					
				
			}
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		mailText.append("\t" + "\t" + "\t" + "\t" + "Total: " + "\t" + "$" + payamt);
		return bpid;
	
}
	private String getBPEmail(int bp_ID) {
	
		MBPartner bp = new MBPartner(getCtx(),bp_ID, get_TrxName());
		MUser[] contacts = bp.getContacts(true);
		
		MUser theContact = null;
		MUser defaultContact = null;
		for(int i = 0; i< contacts.length; i++)
		{
			String name = contacts[i].getName();
			if(name.equalsIgnoreCase("remittance"))
				//TODO: Test contact 'remittance
			{
				theContact = contacts[i];
				break;
			}
			defaultContact = contacts[0];
		}
		
		if(theContact==null)theContact=defaultContact;
		if(theContact==null)
			{
				return "";
			}
			else return theContact.getEMail();
	}
	
	private void successMail(String toAddress) {
			mailResult.append("Successfully sent email to: ");
			mailResult.append(toAddress);
			mailResult.append("\n");
	}
	
	private void sendEmail(int bpID) {
		String to = getBPEmail(bpID);
		if(to == "")//No email for this BP, can't send email.
		{
			MBPartner bp = new MBPartner(getCtx(),bpID, get_TrxName());
			mailResult.append("Failed to send email to: " + bp.getName());
			mailResult.append(". No email address found.");
			mailResult.append("\n");
			return;
		}
		
		MUser currentUser = new MUser(Env.getCtx(), Env.getAD_User_ID(Env.getCtx()),get_TrxName());
		String from = currentUser.getEMail();
		EMail email = new EMail(client, from, to, mailSubject.toString(), mailText.toString());
		if(from==null)throw new AdempiereUserError("Could not find an email address for: " + currentUser.getName() + ". Check your email in your User record.");
		
		String username = currentUser.getEMailUser();
		String password = currentUser.getEMailUserPW();
		email.createAuthenticator(username, password);
		
		if(!email.send().equalsIgnoreCase("OK"))//try twice then write error
			{
				if(!email.send().equalsIgnoreCase("OK"))
				{
					mailResult.append("Failed to send email to: ");
					mailResult.append(to);
					mailResult.append("\n");
				}
				else
				{
					successMail(to);
				}
			}
			else
			{
				successMail(to);
			}
	}
	private void setHeaderAndText() {
		if(!(mMailTextID > 0))
		{
			mailHeader.append(REMITTANCE_HEADER);
			mailBody.append(DEFAULT_MAILBODY);
			subject = DEFAULT_SUBJECT;
		}
		else
		{
			MMailText text = new MMailText(Env.getCtx(), mMailTextID, get_TrxName());
			mailHeader.append(text.getMailText2());
			mailBody.append(text.getMailText());
			subject = text.getMailHeader();
		}
		
		String[] header = mailHeader.toString().split(",");
		StringBuilder tabbedHeader = new StringBuilder();
		for(int x =0; x < header.length; x++)
		{
			tabbedHeader.append(header[x]);
			tabbedHeader.append("\t");
		}
		tabbedHeader.append("\n");
		
		mailHeader=tabbedHeader;
		mailBody.append("\n" + mailHeader);
	}

}

