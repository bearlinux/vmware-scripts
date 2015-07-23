# vmware-scripts
These are miscilanious scripts for working with vmware.  

The basic idea of these vmware groovy scripts is that you start your script off with needed imports  

`
#!/bin/env groovy  
@Grab(group='com.vmware', module='vijava', version='5.1')  

import java.net.URL;
import com.vmware.vim25.*;
import com.vmware.vim25.mo.*;
`

you then  create a connection to the server using a ServiceInstance object

`ServiceInstance si = new ServiceInstance(new URL("https://myserver/sdk/"), "myusername", "mypassword", true);`

You'll want to use an InventoryNavigator to search for things(Datacenter,VirtualMachine,HostSystem,Datastore,etc)

`ManagedEntity[] mes = new InventoryNavigator(si.rootFolder).searchManagedEntities("VirtualMachine"); `

You can loop through using the results and cast the objects appropriately

There is a utility class for converting but you can just cast some things.

`
for (ManagedEntity m : mes) {
	VirtualMachine vm = (VirtualMachine) m;
	println("${vm.getName()} ${vm.getRuntime().getHost().getName()}");
}
`

These scripts will be updated tomorrow. Once I've cleaned them up a little.
