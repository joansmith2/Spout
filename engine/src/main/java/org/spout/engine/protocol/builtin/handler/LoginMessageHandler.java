/*
 * This file is part of Spout.
 *
 * Copyright (c) 2011 Spout LLC <http://www.spout.org/>
 * Spout is licensed under the Spout License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the Spout License Version 1.
 *
 * Spout is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the Spout License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://spout.in/licensev1> for the full license, including
 * the MIT license.
 */
package org.spout.engine.protocol.builtin.handler;

import org.spout.api.event.player.ClientPlayerConnectedEvent;
import org.spout.api.event.player.PlayerConnectEvent;
import org.spout.api.geo.discrete.Point;
import org.spout.api.protocol.ClientSession;
import org.spout.api.protocol.MessageHandler;
import org.spout.api.protocol.ServerSession;
import org.spout.api.protocol.Session;
import org.spout.api.protocol.event.WorldChangeProtocolEvent;

import org.spout.engine.SpoutClient;
import org.spout.engine.SpoutConfiguration;
import org.spout.engine.protocol.builtin.message.LoginMessage;
import org.spout.engine.protocol.builtin.message.ReadyMessage;

public class LoginMessageHandler extends MessageHandler<LoginMessage> {
	@Override
	public void handleServer(ServerSession session, LoginMessage message) {
		session.getEngine().getEventManager().callEvent(new PlayerConnectEvent(session, message.getPlayerName(), (SpoutConfiguration.VIEW_DISTANCE.getInt())));
		// This could be nulled with PlayerConnectEvent
		if (session.hasPlayer()) {
			session.send(Session.SendType.FORCE, new LoginMessage(session.getPlayer().getName(), session.getPlayer().getId()));
			Point ep = session.getPlayer().getPhysics().getPosition();
			session.getPlayer().getNetwork().callProtocolEvent(new WorldChangeProtocolEvent(ep.getWorld()), session.getPlayer());
		}
	}

	@Override
	public void handleClient(ClientSession session, LoginMessage message) {
		session.setState(Session.State.GAME);
		((SpoutClient) session.getEngine()).getPlayer().setName(message.getPlayerName());
		((SpoutClient) session.getEngine()).getPlayer().setId(message.getExtraInt());
		session.getEngine().getEventManager().callEvent(new ClientPlayerConnectedEvent(session, message.getExtraInt()));
	}
}
