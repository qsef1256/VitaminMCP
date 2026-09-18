/**
 * Getting a bot from an open socket to standing in the world.
 *
 * Since 1.20.2 that path runs through the configuration phase, where the server may hold the
 * connection open waiting for answers a headless client has no reason to send on its own.
 * minecraft-protocol answers the vanilla ones (client settings, known packs, finish), and what is
 * left over is here.
 */

/**
 * The client's answers to a resource pack request — vanilla's `ResourcePack.Action` ordinals.
 *
 * Only the two used below are named. `DECLINED` is deliberately not among them: a plugin that
 * forces a pack kicks on a decline, and a bot that cannot join is no better than one that hangs.
 */
const ACCEPTED = 3;
const SUCCESSFULLY_LOADED = 0;

/**
 * Answers every resource pack the server sends, without downloading it.
 *
 * A plugin that pushes a pack from `AsyncPlayerConnectionConfigureEvent` blocks that connection
 * until the client reports what became of the pack, and until then no `PlayerJoinEvent` happens
 * and no play state is entered. mineflayer only *emits* `resourcePack` and leaves the answer to
 * the bot author, so a bot that never answers waits out the whole login timeout on a server that
 * would have let it in. That is what happens on any server hosting its own pack — CraftEngine,
 * Oraxen, ItemsAdder — which is most of them.
 *
 * Nothing is fetched: the URL is usually only reachable from inside the network the server is on,
 * and a test bot has no use for textures. `SUCCESSFULLY_LOADED` is what a client that applied the
 * pack reports, and it is the only answer that satisfies both a required pack and an optional one.
 *
 * `bot.acceptResourcePack()` is not used for this. It answers with mineflayer's own tracked
 * `latestUUID`, which is a `uuid-1345` object where the protocol writer expects the string form —
 * it serialises to the nil UUID, naming a pack the server has never heard of, and the connection
 * goes on waiting. The uuid is echoed straight back off the request here instead.
 */
export function answerResourcePacks(bot) {
  const client = bot._client;

  const answer = (request) => {
    // ACCEPTED first, then the terminal result, in the order a real client reports them: a server
    // that tracks the intermediate state sees the same sequence it would see from a player.
    send(client, request, ACCEPTED);
    send(client, request, SUCCESSFULLY_LOADED);
  };

  // 1.20.3 renamed the request and started identifying packs by uuid; both names are registered
  // because only one of them exists in any given version's protocol.
  client.on('add_resource_pack', answer);
  client.on('resource_pack_send', answer);
}

/** One response, built from whatever identified the request, since that differs by version. */
function send(client, request, result) {
  const response = { result };
  if (request?.uuid !== undefined) {
    response.uuid = request.uuid;
  }
  if (request?.hash !== undefined) {
    response.hash = request.hash;
  }

  try {
    client.write('resource_pack_receive', response);
  } catch (error) {
    // A bot that cannot answer is about to time out with a message that says where it stopped.
    // Throwing from a packet handler would instead take the whole runner, and every other bot in
    // it, down with an unhandled error.
    process.stderr.write(`could not answer a resource pack request: ${error?.message ?? error}\n`);
  }
}

/**
 * Records how far a connection got, so a failure can say so.
 *
 * `did not join within 30000ms` is the same sentence whether the server refused the handshake,
 * dropped the login, or is sitting in configuration waiting for something. The protocol state and
 * the last packet that arrived separate those three at a glance, and cost one listener.
 */
export function traceProgress(bot) {
  const trace = { packet: null };
  bot._client.on('packet', (_data, meta) => {
    trace.packet = meta?.name ?? null;
  });
  return trace;
}

/** Where the connection had got to, in the words the protocol uses for it. */
export function describeProgress(bot, trace) {
  return `last state: ${bot._client?.state ?? 'unknown'}, `
    + `last packet received: ${trace?.packet ?? 'none'}`;
}
