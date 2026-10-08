# app-approver

This is a Slack bot that handles the request approval process for a Slack organization.

## Installation

Create a new Slack app using the [`manifest.json`](manifest.json).

Then install the app to your **Slack Enterprise Organization**, not to a single workspace. You need to be an organization admin/owner.

We recommend using a new admin account for this, so that all actions are performed under this account. And not yours.

Start the program. On the first run, it will automatically generate a `config.properties` file containing all available configuration options.

### Configuration

The configuration file can be specified using the `CONFIG_FILE_PATH` environment variable. If it is not set, `config.properties` in the current working directory is used.

| Config                 | Description                                                         | Required | Default                                      | Environment Variable   |
|------------------------|---------------------------------------------------------------------|---------:|----------------------------------------------|------------------------|
| `server.port`          | The port the HTTP server should listen on.                          |       No | `80`                                         | —                      |
| `server.host`          | The host/interface the server should bind to.                       |       No | `0.0.0.0`                                    | —                      |
| `postgres.url`         | The R2DBC URL of the PostgreSQL database.                           |       No | `r2dbc:postgresql://localhost:5432/postgres` | `POSTGRES_URL`         |
| `postgres.user`        | The username for the PostgreSQL database.                           |       No | `postgres`                                   | `POSTGRES_USER`        |
| `postgres.password`    | The password for the PostgreSQL database.                           |       No | `postgres`                                   | `POSTGRES_PASSWORD`    |
| `slack.bot.token`      | The Slack bot token.                                                |  **Yes** | —                                            | `SLACK_BOT_TOKEN`      |
| `slack.user.token`     | The Slack user token.                                               |  **Yes** | —                                            | `SLACK_USER_TOKEN`     |
| `slack.app.token`      | The Slack app token used for Socket Mode.                           |       No | Empty                                        | `SLACK_APP_TOKEN`      |
| `slack.signing.secret` | The Slack Signing Secret to verify requests from slack in HTTP Mode |       No | Empty                                        | `SLACK_APP_TOKEN`      |
| `slack.channel.review` | The Slack channel used for reviews.                                 |  **Yes** | —                                            | `SLACK_CHANNEL_REVIEW` |
| `slack.channel.log`    | The Slack channel used for review logs.                             |  **Yes** | —                                            | `SLACK_CHANNEL_LOG`    |
| `slack.team.id`        | The Slack team ID.                                                  |  **Yes** | —                                            | `SLACK_TEAM_ID`        |
| `socket.mode`          | Whether to use Slack Socket Mode instead of the HTTP API.           |       No | `false`                                      | `SOCKET_MODE`          |

To restrict a scope, open the `restricted_scope` table in the database and add a new row with the scope you want to restrict.  
The format is the following: `<scope>`, `<type: ALLOWED|RESTRICTED|ALLOWED_FOR_UNVERIFIED>`, `<scopeType: BOTH|USER|BOT>`, `<review: bool>`
- `scope`: The scope to restrict. You can alos use a pattern like `admin.*` to restrict all scopes starting with `admin.` or `*:read` to restrict all scopes ending with `:read`
- `type`: The type of the restriction: `ALLOWED` means allowed for all verified users, `RESTRICTED` means restricted for all users and `ALLOWED_FOR_UNVERIFIED` means even allowed for unverified users.
  - If a scope is not in the database, it is allowed for verified users, but restricted for unverified users.
- `scopeType`: The type of the scope: `BOTH` means both user and bot scopes, `USER` means only user scopes, `BOT` means only bot scopes.
- `review`: Whether to require a review for this scope.
  - `false`: A request containing this scope is automatically rejected.
  - `true`: A request containing this scope is forwarded to the review team.
  - This is only used for `RESTRICTED` scopes.

**Adding a team member:**  
Open the `approve_team_member` table and add a new row with the id of the user you want to add, set the `since` to the current date and time.

#### HTTP API

Slack events are received through the HTTP endpoint:

```text
/slack/events
```

Set in your Slack app the **Request URL** to `/slack/events`, the same for Interactions.

## Approval Flow

When a request is received, the bot checks the following to determinate if the request is valid and should be approved or rejected.

1. **Restricted**: If the application is restricted, the request is rejected.
2. **Enterprise Installation**: If the request is for the entier org, the request is rejected.
3. **Previous denied request**: If the request was previously denied, and nothing changed, the request is also rejected.
4. **Scope evaluation**: All the scopes are checked against the restricted scopes:
    - If there is any no reviewable scope, the request is rejected.
    - If there is any reviewable scope, the request need to be manually reviewed.
    - If the user is not verified and the request not only contains allowed for unverified users scopes, the request is rejected.
5. **Previous approved scopes**: But hold on, let's review the previous approved scopes first.
    - If the previous approved scopes contained any restricted scopes, and this request does not add any new restricted scopes, the request is approved.
    - If this request adds any new restricted scopes, the request is rejected or reviewed within the above rules.

## Rejecting

I talked a lot about rejecting requests above. Actually the request is not rejected, we mark it like that, but on the slack side, it is still pending review.  

This is used because of how slack works: When a member requests an app, a request is created with the pending status.  
If we automatically approve it, we tell slack that it is approved.  
But if the automation rejects it, we dont tell slack that it is rejected. That is because if we do, there is no way to approve it afterward.

New Problem: A user cannot send a new request if they have a pending request. So there is a button to withdraw the request, which cancels the request on slack side, so the user can send a new request.

But there is also a request manual review button, which only change our status to pending review.

**That's the reason why we dont directly tell slack that the request is rejected.**

## Reviewing

Every request that is sent creates a new thread in the review channel, even if it is rejected or approved automatically.

The reason behind this is to allow the reviewer to override the automation decision. Also in the thread all the actions are visible.

## Restricting

The review team can restrict an app. That means that the app can never be requested again. Slack **does not** allow you to do this.

Only use this if absolutely necessary. You can still undo it.

## Status

The status of your requests can be viewed in the App Home of the installed app if you are not a team member.
If you are a team member, you can view all pending requests in the App Home and the last decisions made.

The Bot also DMs the user about the status of their request. And why it was rejected.

There is also a public LOG channel where all requests are logged.