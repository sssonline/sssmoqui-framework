# On-site install notes

Things to remember for clients where Aspen runs on their own hardware instead of Azure. Add to this as on-site installs come up.

## Direct printer integration
Clients that need to print straight to a specific printer (labels, pick tickets, checks) instead of a browser PDF are likely on-site installs, since the server can reach printers on the same network.

- **Starting point in the code:** Moqui's webroot has optional QZ Tray hooks behind the user preference `qz.print.enabled` (`runtime/base-component/webroot/build.gradle:167`). QZ Tray is a print agent installed on the user's PC; the browser hands it the job.
- **Status:** nothing in Aspen uses those hooks yet, so scope and test it per client (printer models, label formats, which screens need it).
- **Questions to ask the client:** which documents print directly, to which printers (make/model, network or USB), and from which workstations.
