On some occasions, the plugin will error out when trying to access cached vote. Seems like
one of the only way to fix it would be to restart the server. Reloading the plugin won't make
the error go away.

After some investigation, it seems like the plugin by default will try to save the 
cache into the /temp folder. However, it's possible that on a shared host, the 
/temp folder could become full, which makes the plugin not able to write the cache.

The fix would be to simply save the cache somewhere else. I've made the plugin
store the cache inside its config folder. This way, it garuntees that there is
always space available to save the cache.
