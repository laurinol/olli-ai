# Team Invitation System

## Overview

This document describes the team invitation system used in Olli-AI, which allows business admins to invite others to join their team.

## Architecture

### Models

- `Invitation`: Core model representing a team invitation. Properties include:
  - `id`: Unique identifier
  - `businessId`: Business the user is invited to join
  - `email`: Email address of the invited user
  - `role`: Role the user will have ("admin" or "user")
  - `sentByUserId`: User ID of the person who sent the invitation
  - `createdAt`: When the invitation was created
  - `expiresAt`: When the invitation expires (7 days after creation)
  - `accepted`: Whether the invitation has been accepted

### Repositories

The system uses a repository pattern with an `InvitationRepository` interface:

- `saveInvitation`: Saves an invitation
- `getInvitation`: Gets an invitation by ID
- `getInvitationsByBusiness`: Gets all invitations for a business
- `getInvitationsByEmail`: Gets all invitations for an email address
- `getInvitationsBySender`: Gets all invitations sent by a user
- `getPendingInvitations`: Gets all pending invitations for a business
- `deleteInvitation`: Deletes an invitation
- `isUserAdmin`: Checks if a user is an admin for a business

Implementations exist for both MongoDB and Firestore.

### Flow

1. **Creation**: A business admin creates an invitation for a user with a specific role
2. **Email**: An email with an invitation link is sent to the user
3. **Acceptance**: The invited user clicks the link and accepts the invitation
4. **Team Joining**: The user is added to the business with the specified role

## Security Considerations

- Only business admins can create team invitations
- Only the invitation creator or a business admin can view, resend, or cancel invitations
- Invitations are tied to specific email addresses
- Invitations expire after 7 days
- Users can only accept invitations sent to their own email address

## API Endpoints

### Creating and Managing Invitations

- `POST /invitations`: Create a new team invitation
- `GET /invitations?businessId={id}`: Get all invitations for a business
- `GET /invitations/pending?businessId={id}`: Get pending invitations for a business
- `GET /invitations/{id}`: Get a specific invitation
- `DELETE /invitations/{id}`: Cancel an invitation
- `POST /invitations/{id}/resend`: Resend an invitation email

### Accepting Invitations

- `GET /invitations/check?email={email}`: Check for invitations to an email address
- `POST /invitations/{id}/accept`: Accept an invitation

## Technical Details

- Invitations expire after 7 days
- The `accepted` flag is set to `true` when the invitation is accepted
- When an invitation is accepted, a BusinessUser record is created

## Frontend Implementation (Pending)

The frontend implementation will include:

1. **Admin Interface**:
   - Invitation creation form
   - List of sent invitations with status
   - Options to resend or cancel invitations

2. **User Interface**:
   - Notification of pending invitations
   - Interface to accept or decline invitations
   - View of teams the user belongs to

## Usage Examples

### Creating an Invitation

```http
POST /invitations
Content-Type: application/json
Authorization: Bearer <token>

{
  "email": "user@example.com",
  "businessId": "business-123",
  "role": "user"
}
```

### Accepting an Invitation

```http
POST /invitations/inv-abc123/accept
Authorization: Bearer <token>
```

## Testing

To test the invitation system:

1. Create an invitation as a business admin
2. Verify the invitation appears in the business's invitations list
3. Check if the invitation can be viewed with the correct invitation ID
4. Accept the invitation as the invited user
5. Verify the user has been added to the business with the correct role