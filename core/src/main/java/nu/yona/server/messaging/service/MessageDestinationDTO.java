/*******************************************************************************
 * Copyright (c) 2016 Stichting Yona Foundation This Source Code Form is subject to the terms of the Mozilla Public License, v.
 * 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *******************************************************************************/
package nu.yona.server.messaging.service;

import java.io.Serializable;
import java.util.UUID;

import nu.yona.server.messaging.entities.MessageDestination;

public class MessageDestinationDTO implements Serializable
{
	private static final long serialVersionUID = -3720093259687281141L;

	private UUID id;

	public MessageDestinationDTO(UUID id)
	{
		if (id == null)
		{
			throw new IllegalArgumentException("id cannot be null");
		}

		this.id = id;
	}

	public static MessageDestinationDTO createInstance(MessageDestination entity)
	{
		return new MessageDestinationDTO(entity.getID());
	}

	public UUID getID()
	{
		return id;
	}
}
