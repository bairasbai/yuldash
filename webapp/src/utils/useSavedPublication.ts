import { useEffect, useState, type RefObject } from "react";
import { checkRidePublication, type PublicationResult } from "../api/ridePublication";
import {
  isCurrentBookingOwner, readPublicationSlot, type BookingCommandOwner, type RidePublication,
} from "./bookingCommandStore";

type SavedPublication = {
  owner: BookingCommandOwner;
  revision: string | null;
  command?: RidePublication;
  result?: PublicationResult;
  storageUnavailable?: boolean;
};

export function useSavedPublication(owner: BookingCommandOwner | null, operation: RefObject<AbortController>) {
  const [saved, setSaved] = useState<SavedPublication>();
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (!owner) return;
    const load = new AbortController();
    const current = () => !load.signal.aborted && isCurrentBookingOwner(owner);
    void readPublicationSlot(owner).then(async ({ command, revision }) => {
      if (!current()) return;
      const initial = { owner, revision, command };
      setSaved(initial);
      if (!command) return;
      const result = await checkRidePublication(owner, command, false, load.signal).catch(() => undefined);
      if (result && current()) setSaved(existing => existing === initial ? { ...initial, result } : existing);
    }, () => {
      if (current()) setSaved({ owner, revision: null, storageUnavailable: true });
    });
    return () => { load.abort(); operation.current?.abort(); };
  }, [owner, revision, operation]);
  return {
    publication: owner && saved?.owner === owner ? saved : undefined,
    setSavedPublication: setSaved,
    reloadPublication() { setSaved(undefined); setRevision(value => value + 1); },
  };
}
