def normalized_user_id:
  (.userId // .user_id) as $user_id
  | if ($user_id | type) == "number" then
      if ($user_id | floor) == $user_id and $user_id >= 1 and $user_id <= 2147483647 then ($user_id | tostring)
      else error("user ID must be a positive integer")
      end
    elif ($user_id | type) == "string" then
      ($user_id | split("_") | last) as $candidate
      | if ($candidate | test("^[0-9]+$")) and (($candidate | tonumber) >= 1) and (($candidate | tonumber) <= 2147483647) then
          ($candidate | tonumber | tostring)
        else error("user ID must normalize to a positive integer")
        end
    else error("event lacks a user ID")
    end;

normalized_user_id
