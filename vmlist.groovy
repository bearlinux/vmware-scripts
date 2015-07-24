#!/bin/env groovy
@Grab(group='com.vmware', module='vijava', version='5.1')
@Grab(group='com.owteam.engUtils', module='netrc', version='2.0.1')
import java.net.URL
import com.vmware.vim25.*
import com.vmware.vim25.mo.*
import com.owteam.engUtils.netrc.Netrc;

def servername = "vcenter.fqdn";
def creds = Netrc.get(servername);
if(creds == null){
	System.err.println("Please add creds for $servername to your .netrc");
}
def si = new ServiceInstance(new URL("https://$servername/sdk"), creds.getUsername(), creds.getPassword(), true)
def rootFolder = si.getRootFolder()
def mes = new InventoryNavigator(rootFolder).searchManagedEntities("VirtualMachine")
mes.each {
    println "${it.getName} : " + it.getConfig().getGuestFullName()
}
si.getServerConnection().logout()

