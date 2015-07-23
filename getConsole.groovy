#!/bin/env groovy
@Grab('com.vmware:vijava:5.1')
@Grab('com.owteam.engUtils:netrc:2.0.0' )
@Grab('com.owteam.engUtils:cert:2.0.0' )

import com.owteam.engUtils.cert.CertTool;
import com.owteam.engUtils.netrc.Netrc;
import java.net.URL;
import com.vmware.vim25.*;
import com.vmware.vim25.mo.*; 

String vmname=args[0];
int htmlPort = 7331;
int port = 443;

String vcenterHost="vcenter.fqdn";

URL vcenterURL=new URL("https://${vcenterHost}/sdk/");
try {

	def creds = Netrc.getInstance().getCredentials(vcenterHost);
	if(creds == null){
		System.err.println("No credentials found, please add a .netrc entry for $vcenterHost");
		System.exit(1);
	}
        ServiceInstance si = new ServiceInstance(vcenterURL, creds.getUserName(), creds.getPassword(), true);
        Folder rootFolder = si.getRootFolder();
        ManagedEntity vmm = new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine",vmname);
	if(vmm==null){
		System.err.println("Virtual Machine ${vmname} not found, exiting");
		System.exit(1);
	}
	SessionManager sm = si.getSessionManager();
	String sessionTicket = sm.acquireCloneTicket();
	String vmId = vmm.getMOR().getVal();

	// Yes I wrote a utility class to make this smaller and so I didn't have to call openssl... 
	// would have had to do openssl s_client -connect $server:$port < /dev/null 2>/dev/null | openssl x509 -fingerprint -noout -in /dev/stdin | awk -F = '{print $2}'`
	String thumbprint = CertTool.fingerprint(CertTool.getCerts(vcenterURL)[0]);

	// VM console URL
	//print "http://" . $server . ":" . $htmlPort . "/console/?vmId=" . $vm_mo_ref_id . "&vmName=" . $vmname . "&host=" . $vcenter_fqdn . "&sessionTicket=" . $session . "&thumbprint=" . $vcenterSSLThumbprint . "\n"
	println("http://${vcenterHost}:$htmlPort/console/?vmId=${vmId}&vmName=${vmname}&host=${vcenterHost}&sessionTicket=${sessionTicket}&thumbprint=${thumbprint}");

} catch (Exception e) {
	e.printStackTrace();
}   
